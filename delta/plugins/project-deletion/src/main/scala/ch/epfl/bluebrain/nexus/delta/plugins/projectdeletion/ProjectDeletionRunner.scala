package ch.epfl.bluebrain.nexus.delta.plugins.projectdeletion

import ch.epfl.bluebrain.nexus.delta.plugins.projectdeletion.ProjectDeleter.projectDeletionPass
import ch.epfl.bluebrain.nexus.delta.plugins.projectdeletion.model.ProjectDeletionConfig
import ch.epfl.bluebrain.nexus.delta.sdk.ProjectResource
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.Pagination
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchParams.ProjectSearchParams
import ch.epfl.bluebrain.nexus.delta.sdk.projects.{Projects, ProjectsStatistics}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.{CompiledProjection, ExecutionStrategy, ProjectionMetadata, Supervisor}
import com.typesafe.scalalogging.Logger
import fs2.Stream
import monix.bio.{Task, UIO}

import java.time.Instant

class ProjectDeletionRunner(projects: Projects, config: ProjectDeletionConfig, projectStatistics: ProjectsStatistics) {

  private val logger: Logger = Logger[ProjectDeletionRunner]

  private def lastEventTime(pr: ProjectResource, now: Instant): UIO[Instant] = {
    projectStatistics
      .get(pr.value.ref)
      .map(_.map(_.lastEventTime).getOrElse {
        logger.error(s"Statistics for project '${pr.value.ref}' were not found")
        now
      })
  }

  private val allProjects: UIO[Seq[ProjectResource]] = {
    projects
      .list(
        Pagination.OnePage,
        ProjectSearchParams(filter = _ => UIO.pure(true)),
        Ordering.by(_.updatedAt) // this is not needed, we are forced to specify an ordering
      )
      .map(_.results)
      .map(_.map(_.source))
  }

  private def deleteProject(pr: ProjectResource): UIO[Unit] = {
    implicit val caller: Subject = Identity.Anonymous
    projects
      .delete(pr.value.ref, pr.rev)
      .void
      .onErrorHandleWith(e => UIO.eval(logger.error(s"Error deleting project from plugin: $e")))
      .void
  }

  def doProjectDeletionPass(): UIO[Unit] = {
    projectDeletionPass(allProjects, deleteProject, config, lastEventTime)
  }
}

object ProjectDeletionRunner {
  private val projectionMetadata: ProjectionMetadata = ProjectionMetadata("system", "project-deletion", None, None)

  /**
    * Constructs a ProjectDeletionRunner process that is started in the supervisor.
    */
  def start(
      projects: Projects,
      config: ProjectDeletionConfig,
      projectStatistics: ProjectsStatistics,
      supervisor: Supervisor
  ): Task[ProjectDeletionRunner] = {

    val runner = new ProjectDeletionRunner(projects, config, projectStatistics)

    val continuousStream = Stream
      .fixedRate[Task](config.idleCheckPeriod)
      .evalMap(_ => runner.doProjectDeletionPass())
      .drain

    val compiledProjection =
      CompiledProjection.fromStream(projectionMetadata, ExecutionStrategy.TransientSingleNode, _ => continuousStream)

    supervisor
      .run(compiledProjection)
      .map(_ => runner)
  }
}
