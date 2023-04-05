package ch.epfl.bluebrain.nexus.delta.plugins.projectdeletion

import ch.epfl.bluebrain.nexus.delta.kernel.utils.IOUtils
import ch.epfl.bluebrain.nexus.delta.plugins.projectdeletion.model.ProjectDeletionConfig
import ch.epfl.bluebrain.nexus.delta.sdk.ProjectResource
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.Pagination
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchParams.ProjectSearchParams
import ch.epfl.bluebrain.nexus.delta.sdk.plugin.Plugin
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ProjectRejection
import ch.epfl.bluebrain.nexus.delta.sdk.projects.{Projects, ProjectsStatistics}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity
import com.typesafe.scalalogging.Logger
import fs2.Stream
import monix.bio.{Fiber, IO, Task, UIO}
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject

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
  implicit private class BooleanTaskOps(val left: Boolean) extends AnyVal {
    def `<||>`(right: Task[Boolean]): Task[Boolean] = {
      if (left) {
        Task.pure(true)
      } else {
        right
      }
    }

    def `<&&>`(right: Task[Boolean]): Task[Boolean] = {
      if (left) {
        right
      } else {
        Task.pure(false)
      }
    }
  }

  def projectDeletionPass(
      allProjects: UIO[Seq[ProjectResource]],
      deleteProject: ProjectResource => IO[ProjectRejection, Unit],
      config: ProjectDeletionConfig,
      lastEventTime: ProjectResource => Task[Instant]
  ): Task[Unit] = {

    def isIncluded(pr: ProjectResource): Boolean = {
      config.includedProjects.exists(regex => regex.matches(pr.value.ref.toString))
    }

    def notExcluded(pr: ProjectResource): Boolean = {
      !config.excludedProjects.exists(regex => regex.matches(pr.value.ref.toString))
    }

    def deletableDueToDeprecation(pr: ProjectResource): Boolean = {
      config.deleteDeprecatedProjects && pr.deprecated
    }

    def deletableDueToBeingIdle(pr: ProjectResource, now: Instant): Task[Boolean] = {
      projectIsIdle(pr, now) <&&> resourcesAreIdle(pr, now)
    }

    def projectIsIdle(pr: ProjectResource, now: Instant) = {
      (now diff pr.updatedAt).toSeconds > config.idleInterval.toSeconds
    }

    def resourcesAreIdle(pr: ProjectResource, now: Instant) = {
      lastEventTime(pr).map(_.isBefore(now.minus(Duration.ofMillis(config.idleInterval.toMillis))))
    }

    def allProjectsNotAlreadyDeleted: IO[Nothing, Seq[ProjectResource]] = {
      allProjects
        .map(_.filter(!_.value.markedForDeletion))
    }

    def shouldBeDeleted(now: Instant): ProjectResource => Task[Boolean] = { pr =>
      {
        (isIncluded(pr) && notExcluded(pr)) <&&> (deletableDueToDeprecation(pr) <||> deletableDueToBeingIdle(pr, now))
      }
    }

    def deleteProjects(projects: Seq[ProjectResource]): Task[Unit] = {
      Task
        .traverse(projects)(
          deleteProject(_).void.mapError(e => new RuntimeException(s"error deleting project from plugin: ${e}"))
        )
        .void
    }

    for {
      allProjects      <- allProjectsNotAlreadyDeleted
      now              <- IOUtils.instant
      projectsToDelete <- allProjects.filterA[Task](shouldBeDeleted(now))
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

    def lastEventTime(pr: ProjectResource): Task[Instant] = {
      projectStatistics.get(pr.value.ref).flatMap {
        case Some(stats) => IO.pure(stats.lastEventTime)
        case None        => IO.raiseError(new IllegalArgumentException(s"Project '${pr.value.ref}' was not found."))
      }
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

    def deleteProject(pr: ProjectResource): IO[ProjectRejection, Unit] = {
      implicit val caller: Subject = Identity.Anonymous
      projects
        .delete(pr.value.ref, pr.rev)
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
