package ch.epfl.bluebrain.nexus.delta.sdk.deletion

import cats.effect.{Clock, IO}
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.kernel.{Logger, RetryStrategy}
import ch.epfl.bluebrain.nexus.delta.sdk.deletion.model.ProjectDeletionReport
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.ServiceAccount
import ch.epfl.bluebrain.nexus.delta.sdk.projects.ProjectsConfig.DeletionConfig
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ProjectState
import ch.epfl.bluebrain.nexus.delta.sdk.projects.{Projects, ProjectsConfig}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sourcing.Transactors
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{ElemStream, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.stream._
import fs2.Stream

/**
  * Stream to delete project from the system after those are marked as deleted
  */
sealed trait ProjectDeletionCoordinator

object ProjectDeletionCoordinator {

  private val logger = Logger[ProjectDeletionCoordinator]

  /**
    * If deletion is disabled, we do nothing
    */
  final private[deletion] case object Noop extends ProjectDeletionCoordinator

  /**
    * If deletion is enabled, we go through the project state log, looking for those marked for deletion
    */
  final private[deletion] class Active(
      fetchProjects: Offset => ElemStream[ProjectState],
      deletionTasks: List[ProjectDeletionTask],
      deletionConfig: DeletionConfig,
      serviceAccount: ServiceAccount,
      deletionStore: ProjectDeletionStore,
      clock: Clock[IO]
  ) extends ProjectDeletionCoordinator {

    implicit private val serviceAccountSubject: Subject = serviceAccount.subject

    def run(offset: Offset): Stream[IO, Elem[Unit]] =
      fetchProjects(offset).evalMap {
        _.traverse {
          case project if project.markedForDeletion =>
            // If it fails, we try again after a backoff
            val retryStrategy = RetryStrategy.retryOnNonFatal(
              deletionConfig.retryStrategy,
              logger,
              s"attempting to delete project ${project.project}"
            )
            delete(project).retry(retryStrategy)
          case _                                    => IO.unit
        }
      }

    private[deletion] def delete(project: ProjectState): IO[Unit] =
      for {
        _         <- logger.warn(s"Starting deletion of project ${project.project}")
        now       <- clock.realTimeInstant
        // Running preliminary tasks before deletion like deprecating and stopping views,
        // removing acl related to the project, etc...
        initReport = ProjectDeletionReport(project.project, project.updatedAt, now, project.updatedBy)
        report    <- deletionTasks
                       .foldLeftM(initReport) { case (report, task) =>
                         task(project.project).map(report ++ _)
                       }
        // Waiting for events issued by deletion tasks to be taken into account
        _         <- IO.sleep(deletionConfig.propagationDelay)
        // Delete the events and states and save the deletion report
        _         <- deletionStore.deleteAndSaveReport(report)
        _         <- logger.info(s"Project ${project.project} has been successfully deleted.")
      } yield ()

    private[deletion] def list(project: ProjectRef): IO[List[ProjectDeletionReport]] =
      deletionStore.list(project)
  }

  /**
    * Build the project deletion stream according to the configuration
    */
  def apply(
      projects: Projects,
      deletionTasks: Set[ProjectDeletionTask],
      deletionConfig: ProjectsConfig.DeletionConfig,
      serviceAccount: ServiceAccount,
      xas: Transactors,
      clock: Clock[IO]
  ): ProjectDeletionCoordinator =
    if (deletionConfig.enabled) {
      new Active(
        projects.states,
        deletionTasks.toList,
        deletionConfig,
        serviceAccount,
        new ProjectDeletionStore(xas),
        clock
      )
    } else
      Noop

  /**
    * Build and run the project deletion stream in the supervisor
    */
  // $COVERAGE-OFF$
  def apply(
      projects: Projects,
      deletionTasks: Set[ProjectDeletionTask],
      deletionConfig: ProjectsConfig.DeletionConfig,
      serviceAccount: ServiceAccount,
      supervisor: Supervisor,
      xas: Transactors,
      clock: Clock[IO]
  ): IO[ProjectDeletionCoordinator] = {
    val stream = apply(projects, deletionTasks, deletionConfig, serviceAccount, xas, clock)
    stream match {
      case Noop           => logger.info("Projection deletion is disabled.").as(Noop)
      case active: Active =>
        val metadata: ProjectionMetadata = ProjectionMetadata("system", "project-deletion", None, None)
        supervisor
          .run(
            CompiledProjection.fromStream(
              metadata,
              ExecutionStrategy.PersistentSingleNode,
              active.run
            )
          )
          .as(active)
    }
  }
  // $COVERAGE-ON$
}
