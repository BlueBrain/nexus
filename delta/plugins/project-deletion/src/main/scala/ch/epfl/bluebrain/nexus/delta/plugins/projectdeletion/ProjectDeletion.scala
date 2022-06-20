package ch.epfl.bluebrain.nexus.delta.plugins.projectdeletion

import akka.persistence.query.{NoOffset, Offset, TimeBasedUUID}
import ch.epfl.bluebrain.nexus.delta.kernel.Mapper
import ch.epfl.bluebrain.nexus.delta.kernel.utils.IOUtils
import ch.epfl.bluebrain.nexus.delta.plugins.projectdeletion.model.ProjectDeletionConfig
import ch.epfl.bluebrain.nexus.delta.sdk.eventlog.EventLogUtils
import ch.epfl.bluebrain.nexus.delta.sdk.model.Envelope
import ch.epfl.bluebrain.nexus.delta.sdk.model.Event.ProjectScopedEvent
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRejection.ProjectNotFound
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.Pagination
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchParams.ProjectSearchParams
import ch.epfl.bluebrain.nexus.delta.sdk.plugin.Plugin
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.{ProjectResource, Projects}
import ch.epfl.bluebrain.nexus.delta.sourcing.EventLog
import ch.epfl.bluebrain.nexus.delta.sourcing.config.DatabaseFlavour
import com.datastax.oss.driver.api.core.uuid.Uuids
import com.typesafe.scalalogging.Logger
import fs2.Stream
import monix.bio.{Fiber, Task, UIO}

import java.time.Instant
import scala.concurrent.duration.DurationInt

/**
  * ProjectDeletion process reference, also the plugin instance that allows for graceful stop. Once the instance is
  * constructed, the process would be already running.
  *
  * @param fiber
  *   the underlying process fiber
  */
class ProjectDeletion(fiber: Fiber[Throwable, Unit]) extends Plugin {

  private val logger: Logger = Logger[ProjectDeletion]

  logger.info("Project Deletion periodic check has started.")

  /**
    * Stop the plugin. This should allow the plugin to terminate gracefully.
    */
  override def stop(): Task[Unit] = fiber.cancel.timeout(10.seconds).flatMap {
    case Some(()) => UIO.delay(logger.info("Project Deletion plugin stopped."))
    case None     => UIO.delay(logger.error("Project Deletion plugin could not be stopped in 10 seconds."))
  }
}

object ProjectDeletion {

  /**
    * Constructs a ProjectDeletion process that is started in the background while returning a reference for a graceful
    * stop.
    *
    * @param projects
    *   the projects interface
    * @param config
    *   the automatic project deletion configuration
    * @param eventLog
    *   a reference to the project evenlog interface
    * @param deleteProject
    *   the delete project logic
    */
  def apply(
      projects: Projects,
      config: ProjectDeletionConfig,
      eventLog: EventLog[Envelope[ProjectScopedEvent]],
      deleteProject: DeleteProject
  ): Task[ProjectDeletion] = {

    // the logic for project deletion:
    // 1. iterate over the list of projects at a specific time interval (e.g. hourly)
    // 2. exclude from the list of projects the ones that are already marked for deletion (deletion is asynchronous, and
    //    the project metadata will include whether deletion has started for a project)
    // 3. check the idle interval for each project and preserve only the projects that are considered idle; if the
    //    config is set to delete deprecated projects, preserve those projects as well even if not considered idle.
    // 4. check the inclusion filter and retain only the projects that match one of the configured regexes
    // 5. check the exclusion filter and exclude the projects that match one of the configured regexes
    // 6. delete the remaining projects
    // 7. repeat

    // consider for deletion only the projects that match one of the configured regexes
    def inclusionFilter(pr: ProjectResource): Boolean =
      config.includedProjects.exists(regex => regex.matches(pr.value.ref.toString))

    // consider for deletion only the projects that do NOT match one of the configured regexes
    def exclusionFilter(pr: ProjectResource): Boolean =
      !config.excludedProjects.exists(regex => regex.matches(pr.value.ref.toString))

    // a filter on projects that depends on the deprecation configuration
    def deprecationFilter(pr: ProjectResource): Boolean =
      if (config.deleteDeprecatedProjects) pr.deprecated
      else false

    // a filter on projects that retains only the projects that were last updated more than the idle interval ago
    def lastUpdatedFilter(pr: ProjectResource, now: Instant): Boolean =
      (now diff pr.updatedAt).toSeconds > config.idleInterval.toSeconds

    // computes the event log start offset based on the current database flavour (postgres does not support replaying
    // logs from a specific point in time)
    def offset(now: Instant): Offset =
      eventLog.config.flavour match {
        case DatabaseFlavour.Postgres  => NoOffset
        case DatabaseFlavour.Cassandra => TimeBasedUUID(Uuids.startOf(now.toEpochMilli - config.idleInterval.toMillis))
      }

    // verify the event stream for events more recent than (now - idle interval); if any events are found the project
    // deletion will ignore this project
    def checkAndDeleteProject(pr: ProjectResource, now: Instant): Task[Unit] = {
      implicit val rm: Mapper[ProjectNotFound, Throwable] =
        Mapper(_ => new IllegalArgumentException(s"Project '${pr.value.ref}' was not found."))
      EventLogUtils
        .currentProjectEvents(projects, eventLog, pr.value.ref, offset(now))
        .flatMap { stream =>
          stream
            .filter(
              _.instant.getEpochSecond > (now.getEpochSecond - config.idleInterval.toSeconds)
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

    // the continuous stream that checks for projects that are idle and starts the deletion logic
    val stream = Stream
      .fixedRate[Task](config.idleCheckPeriod)
      .evalMap { _ =>
        val projectResources: UIO[Seq[ProjectResource]] = projects
          .list(
            Pagination.OnePage,
            ProjectSearchParams(filter = project => UIO.pure(!project.markedForDeletion)),
            Ordering.by(_.updatedAt)
          )
          .map(_.results.map(_.source))

        val toCheck: UIO[(Instant, Seq[ProjectResource])] = IOUtils.instant.flatMap { now =>
          projectResources.map { candidates =>
            now -> candidates.filter { pr =>
              (deprecationFilter(pr) || lastUpdatedFilter(pr, now)) && inclusionFilter(pr) && exclusionFilter(pr)
            }
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
