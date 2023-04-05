package ch.epfl.bluebrain.nexus.delta.plugins.projectdeletion

import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.kernel.utils.IOUtils
import ch.epfl.bluebrain.nexus.delta.plugins.projectdeletion.model.ProjectDeletionConfig
import ch.epfl.bluebrain.nexus.delta.sdk.ProjectResource
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.Pagination
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchParams.ProjectSearchParams
import ch.epfl.bluebrain.nexus.delta.sdk.plugin.Plugin
import ch.epfl.bluebrain.nexus.delta.sdk.projects.{Projects, ProjectsStatistics}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import com.typesafe.scalalogging.Logger
import fs2.Stream
import monix.bio.{Fiber, IO, Task, UIO}

import java.time.{Duration, Instant}
import scala.concurrent.duration.DurationInt

/**
  * ProjectDeletion process reference, also the plugin instance that allows for graceful stop. Once the instance is
  * constructed, the process would be already running.
  *
  * @param fiber
  *   the underlying process fiber which controls the process
  */
class ProjectDeletionPlugin private (fiber: Fiber[Throwable, Unit]) extends Plugin {

  private val logger: Logger = Logger[ProjectDeletionPlugin]
  logger.info("Project Deletion periodic check has started.")

  override def stop(): Task[Unit] = fiber.cancel.timeout(10.seconds).flatMap {
    case Some(()) => UIO.delay(logger.info("Project Deletion plugin stopped."))
    case None     => UIO.delay(logger.error("Project Deletion plugin could not be stopped in 10 seconds."))
  }
}

object ProjectDeletionPlugin {

  private val logger: Logger = Logger[ProjectDeletionPlugin]
  implicit private class BooleanTaskOps(val left: Boolean) extends AnyVal {
    def `<||>`(right: UIO[Boolean]): UIO[Boolean] = {
      if (left) {
        UIO.pure(true)
      } else {
        right
      }
    }

    def `<&&>`(right: UIO[Boolean]): UIO[Boolean] = {
      if (left) {
        right
      } else {
        UIO.pure(false)
      }
    }
  }

  def projectDeletionPass(
      allProjects: UIO[Seq[ProjectResource]],
      deleteProject: ProjectResource => UIO[Unit],
      config: ProjectDeletionConfig,
      lastEventTime: (ProjectResource, Instant) => UIO[Instant]
  ): UIO[Unit] = {

    def isIncluded(pr: ProjectResource): Boolean = {
      config.includedProjects.exists(regex => regex.matches(pr.value.ref.toString))
    }

    def notExcluded(pr: ProjectResource): Boolean = {
      !config.excludedProjects.exists(regex => regex.matches(pr.value.ref.toString))
    }

    def deletableDueToDeprecation(pr: ProjectResource): Boolean = {
      config.deleteDeprecatedProjects && pr.deprecated
    }

    def deletableDueToBeingIdle(pr: ProjectResource, now: Instant): UIO[Boolean] = {
      projectIsIdle(pr, now) <&&> resourcesAreIdle(pr, now)
    }

    def projectIsIdle(pr: ProjectResource, now: Instant) = {
      (now diff pr.updatedAt).toSeconds > config.idleInterval.toSeconds
    }

    def resourcesAreIdle(pr: ProjectResource, now: Instant): UIO[Boolean] = {
      lastEventTime(pr, now).map(_.isBefore(now.minus(Duration.ofMillis(config.idleInterval.toMillis))))
    }

    def allProjectsNotAlreadyDeleted: IO[Nothing, Seq[ProjectResource]] = {
      allProjects
        .map(_.filter(!_.value.markedForDeletion))
    }

    def shouldBeDeleted(now: Instant): ProjectResource => UIO[Boolean] = { pr =>
      {
        (isIncluded(pr) && notExcluded(pr)) <&&> (deletableDueToDeprecation(pr) <||> deletableDueToBeingIdle(pr, now))
      }
    }

    def deleteProjects(projects: Seq[ProjectResource]): UIO[Unit] = {
      projects.traverse(deleteProject).void
    }

    for {
      allProjects      <- allProjectsNotAlreadyDeleted
      now              <- IOUtils.instant
      projectsToDelete <- allProjects.filterA(shouldBeDeleted(now))
      _                <- deleteProjects(projectsToDelete)
    } yield {
      ()
    }
  }

  /**
    * Constructs a stoppable ProjectDeletion process that is started in the background.
    */
  def started(
      projects: Projects,
      config: ProjectDeletionConfig,
      projectStatistics: ProjectsStatistics
  ): Task[ProjectDeletionPlugin] = {

    def lastEventTime(pr: ProjectResource, now: Instant): UIO[Instant] = {
      projectStatistics
        .get(pr.value.ref)
        .map(_.map(_.lastEventTime).getOrElse {
          logger.error(s"statistics for project '${pr.value.ref}' were not found")
          now
        })
    }

    val allProjects: UIO[Seq[ProjectResource]] = {
      projects
        .list(
          Pagination.OnePage,
          ProjectSearchParams(filter = _ => UIO.pure(true)),
          Ordering.by(_.updatedAt) // this is not needed, we are forced to specify an ordering
        )
        .map(_.results)
        .map(_.map(_.source))
    }

    def deleteProject(pr: ProjectResource): UIO[Unit] = {
      implicit val caller: Subject = Identity.Anonymous
      projects
        .delete(pr.value.ref, pr.rev)
        .void
        .onErrorHandle(e => logger.error(s"error deleting project from plugin: ${e}"))
        .void
    }

    val continuousStream = Stream
      .fixedRate[Task](config.idleCheckPeriod)
      .evalMap(_ => projectDeletionPass(allProjects, deleteProject, config, lastEventTime))
      .compile
      .drain
      .absorb

    continuousStream.onErrorHandleWith(_ => continuousStream).start.map { (fiber: Fiber[Throwable, Unit]) =>
      new ProjectDeletionPlugin(fiber)
    }
  }
}
