package ch.epfl.bluebrain.nexus.delta.plugins.projectdeletion

import akka.persistence.query.{NoOffset, Offset, TimeBasedUUID}
import ch.epfl.bluebrain.nexus.delta.kernel.Mapper
import ch.epfl.bluebrain.nexus.delta.plugins.projectdeletion.model.ProjectDeletionConfig
import ch.epfl.bluebrain.nexus.delta.sdk.eventlog.EventLogUtils
import ch.epfl.bluebrain.nexus.delta.sdk.model.Envelope
import ch.epfl.bluebrain.nexus.delta.sdk.model.Event.ProjectScopedEvent
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.{Caller, ServiceAccount}
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRejection.ProjectNotFound
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.Pagination
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchParams.ProjectSearchParams
import ch.epfl.bluebrain.nexus.delta.sdk.plugin.Plugin
import ch.epfl.bluebrain.nexus.delta.sdk.{ProjectReferenceFinder, ProjectResource, Projects}
import ch.epfl.bluebrain.nexus.delta.sourcing.EventLog
import ch.epfl.bluebrain.nexus.delta.sourcing.config.DatabaseFlavour
import com.datastax.oss.driver.api.core.uuid.Uuids
import com.typesafe.scalalogging.Logger
import fs2.Stream
import monix.bio.{Fiber, IO, Task, UIO}

import java.time.Instant
import scala.concurrent.duration.DurationInt

class ProjectDeletion(fiber: Fiber[Throwable, Unit]) extends Plugin {

  private val logger: Logger = Logger[ProjectDeletion]

  /**
    * Stop the plugin. This should allow the plugin to terminate gracefully.
    */
  override def stop(): Task[Unit] = fiber.cancel.timeout(10.seconds).flatMap {
    case Some(()) => UIO.delay(logger.info("Project Deletion plugin stopped."))
    case None     => UIO.delay(logger.error("Project Deletion plugin could not be stopped in 10 seconds."))
  }
}

object ProjectDeletion {

  def apply(
      projects: Projects,
      config: ProjectDeletionConfig,
      serviceAccount: ServiceAccount,
      eventLog: EventLog[Envelope[ProjectScopedEvent]]
  )(implicit prf: ProjectReferenceFinder): Task[ProjectDeletion] = {

    implicit val caller: Caller = serviceAccount.caller
    val logger: Logger          = Logger[ProjectDeletion]

    // a filter on projects that depends on the deprecation configuration
    def deprecationFilter(pr: ProjectResource): Boolean =
      if (config.deleteDeprecatedProjects) pr.deprecated
      else true

    def lastUpdatedFilter(pr: ProjectResource, now: Instant): Boolean =
      (now.getEpochSecond - pr.updatedAt.getEpochSecond) > config.idleInterval.toSeconds

    def offset(now: Instant): Offset =
      eventLog.flavour match {
        case DatabaseFlavour.Postgres  => NoOffset
        case DatabaseFlavour.Cassandra => TimeBasedUUID(Uuids.startOf(now.toEpochMilli - config.idleInterval.toMillis))
      }

    def deleteProject(pr: ProjectResource): UIO[Unit] =
      projects
        .delete(pr.value.ref, pr.rev)
        .flatMap { case (uuid, _) =>
          UIO.delay(logger.info(s"Marked project '${pr.value.ref}' for deletion ($uuid)."))
        }
        .onErrorHandleWith { rejection =>
          UIO.delay(
            logger.error(
              s"Unable to mark project '${pr.value.ref}' for deletion due to '${rejection.reason}'. It will be retried at the next pass."
            )
          )
        }

    def checkAndDeleteProject(pr: ProjectResource, now: Instant): Task[Unit] = {
      implicit val rm: Mapper[ProjectNotFound, Throwable] =
        Mapper(_ => new IllegalArgumentException(s"Project '${pr.value.ref}' was not found."))
      EventLogUtils
        .currentProjectEvents(projects, eventLog, pr.value.ref, offset(now))
        .flatMap { stream =>
          stream
            .filter(
              _.instant.getEpochSecond < (now.getEpochSecond - config.idleInterval.toSeconds)
            ) // need to discard events because the postgres backend will not make use of the timestamp
            .take(1L)
            .compile
            .last // find at least one event in the time interval
        }
        .flatMap {
          case Some(_) => UIO.unit          // if there's at least one event don't do anything
          case None    => deleteProject(pr) // delete project when there are no events
        }
    }

    val stream = Stream
      .fixedRate[Task](config.idleCheckPeriod)
      .evalMap { _ =>
        val projectResources: UIO[Seq[ProjectResource]] = projects
          .list(
            Pagination.OnePage,
            ProjectSearchParams(filter = project => !project.markedForDeletion),
            Ordering.by(_.updatedAt)
          )
          .map(_.results.map(_.source))

        val toCheck: UIO[(Instant, Seq[ProjectResource])] = IO.clock[Nothing].instantNow.flatMap { now =>
          projectResources.map { candidates =>
            now -> candidates.filter(pr => deprecationFilter(pr) || lastUpdatedFilter(pr, now))
          }
        }

        toCheck.flatMap { case (now, prs) =>
          Task
            .traverse(prs) { pr =>
              if (config.deleteDeprecatedProjects && pr.deprecated) deleteProject(pr)
              else checkAndDeleteProject(pr, now)
            }
            .void
        }
      }
      .compile
      .drain
      .absorb

    stream.onErrorHandleWith(_ => stream).start.map { (fiber: Fiber[Throwable, Unit]) =>
      new ProjectDeletion(fiber)
    }
  }

}
