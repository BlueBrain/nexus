package ai.senscience.nexus.delta.projectdeletion

import ai.senscience.nexus.delta.projectdeletion.model.ProjectDeletionConfig
import cats.effect.{Clock, IO}
import cats.implicits.*
import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import ch.epfl.bluebrain.nexus.delta.kernel.search.Pagination
import ch.epfl.bluebrain.nexus.delta.sdk.ProjectResource
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchParams.ProjectSearchParams
import ch.epfl.bluebrain.nexus.delta.sdk.projects.{Projects, ProjectsStatistics}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.{CompiledProjection, ExecutionStrategy, ProjectionMetadata, Supervisor}
import fs2.Stream

import java.time.Instant

class ProjectDeletionRunner(projects: Projects, config: ProjectDeletionConfig, projectStatistics: ProjectsStatistics) {

  private val logger = Logger[ProjectDeletionRunner]

  private def lastEventTime(pr: ProjectResource, now: Instant): IO[Instant] = {
    projectStatistics
      .get(pr.value.ref)
      .map(_.map(_.lastEventTime).getOrElse {
        logger.error(s"Statistics for project '${pr.value.ref}' were not found")
        now
      })
  }

  private val allProjects: IO[Seq[ProjectResource]] = {
    projects
      .list(
        Pagination.OnePage,
        ProjectSearchParams(filter = _ => IO.pure(true)),
        Ordering.by(_.updatedAt) // this is not needed, we are forced to specify an ordering
      )
      .map(_.results)
      .map(_.map(_.source))
  }

  private def deleteProject(pr: ProjectResource): IO[Unit] = {
    implicit val caller: Subject = Identity.Anonymous
    projects
      .delete(pr.value.ref, pr.rev)
      .handleErrorWith(e => logger.error(s"Error deleting project from plugin: $e"))
      .void
  }

  def projectDeletionPass(clock: Clock[IO]): IO[Unit] = {

    val shouldDeleteProject = ShouldDeleteProject(config, lastEventTime, clock)

    def possiblyDelete(project: ProjectResource): IO[Unit] = {
      shouldDeleteProject(project).flatMap {
        case true  => deleteProject(project)
        case false => IO.unit
      }
    }

    allProjects
      .flatMap(_.traverse(possiblyDelete))
      .void
  }
}

object ProjectDeletionRunner {
  private val projectionMetadata: ProjectionMetadata =
    ProjectionMetadata("system", "project-automatic-deletion", None, None)

  /**
    * Constructs a ProjectDeletionRunner process that is started in the supervisor.
    */
  def start(
      projects: Projects,
      config: ProjectDeletionConfig,
      projectStatistics: ProjectsStatistics,
      supervisor: Supervisor,
      clock: Clock[IO]
  ): IO[ProjectDeletionRunner] = {

    val runner = new ProjectDeletionRunner(projects, config, projectStatistics)

    val continuousStream = Stream
      .fixedRate[IO](config.idleCheckPeriod)
      .evalMap(_ => runner.projectDeletionPass(clock))
      .drain

    val compiledProjection =
      CompiledProjection.fromStream(projectionMetadata, ExecutionStrategy.TransientSingleNode, _ => continuousStream)

    supervisor
      .run(compiledProjection)
      .map(_ => runner)
  }
}
