package ch.epfl.bluebrain.nexus.delta.plugins.projectdeletion

import ch.epfl.bluebrain.nexus.delta.plugins.projectdeletion.ProjectDeletionLogic.projectDeletionPass
import ch.epfl.bluebrain.nexus.delta.plugins.projectdeletion.model.ProjectDeletionConfig
import ch.epfl.bluebrain.nexus.delta.sdk.ProjectResource
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.Pagination
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchParams.ProjectSearchParams
import ch.epfl.bluebrain.nexus.delta.sdk.plugin.Plugin
import ch.epfl.bluebrain.nexus.delta.sdk.projects.{Projects, ProjectsStatistics}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
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
