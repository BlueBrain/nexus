package ch.epfl.bluebrain.nexus.delta.service.projects

import akka.actor.typed.ActorSystem
import cats.effect.Clock
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategy
import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategy.logError
import ch.epfl.bluebrain.nexus.delta.kernel.kamon.KamonMetricsConfig
import ch.epfl.bluebrain.nexus.delta.kernel.utils.{IOUtils, UUIDF}
import ch.epfl.bluebrain.nexus.delta.sdk.cache.KeyValueStoreConfig
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourcesDeletionProgress.Deleting
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectEvent
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectEvent.ProjectMarkedForDeletion
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Envelope, ResourcesDeletionProgress, ResourcesDeletionStatus, ResourcesDeletionStatusCollection}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.{Projects, ResourcesDeletion}
import ch.epfl.bluebrain.nexus.delta.service.projects.ProjectsImpl.DeletionStatusCache
import ch.epfl.bluebrain.nexus.delta.sourcing.EventLog
import ch.epfl.bluebrain.nexus.delta.sourcing.config.SaveProgressConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.Projection
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.ProjectionId.CacheProjectionId
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.stream.DaemonStreamCoordinator
import com.typesafe.scalalogging.Logger
import fs2.Stream
import monix.bio.{Task, UIO}
import monix.execution.Scheduler

import java.util.UUID

class ProjectsDeletionStream(
    eventLog: EventLog[Envelope[ProjectMarkedForDeletion]],
    projects: Projects,
    cache: DeletionStatusCache,
    projection: Projection[ResourcesDeletionStatusCollection],
    resourcesDeletion: ResourcesDeletion
)(implicit
    clock: Clock[UIO],
    uuidF: UUIDF,
    as: ActorSystem[Nothing],
    sc: Scheduler,
    persistProgressConfig: SaveProgressConfig,
    keyValueStoreConfig: KeyValueStoreConfig
) {

  private val projectionId: CacheProjectionId            = CacheProjectionId("ProjectsDeletionProgress")
  implicit private val metricsConfig: KamonMetricsConfig = KamonMetricsConfig(projectionId.value, Map.empty)
  private val logger: Logger                             = Logger[ProjectsDeletionStream]

  final def run(): Task[Unit] = {
    val retryStrategy =
      RetryStrategy[Throwable](keyValueStoreConfig.retry, _ => true, logError(logger, "projects deletion"))

    DaemonStreamCoordinator.run("ProjectsDeletionProgress", buildStream, retryStrategy)
  }

  private def deletionAction(event: ProjectEvent, uuid: UUID, action: Task[ResourcesDeletionProgress]) =
    for {
      progress  <- action
      status    <- getStatus(event, uuid)
      instant   <- IOUtils.instant
      nextStatus = status.copy(progress = progress, updatedAt = instant)
      _         <- cache.put(uuid, nextStatus)
    } yield nextStatus

  private def getStatus(event: ProjectEvent, uuid: UUID) =
    cache.get(uuid).flatMap {
      case Some(status) => UIO.pure(status)
      case None         =>
        projects
          .fetch(event.project)
          .mapError(rej => new IllegalArgumentException(rej.reason))
          .map(resource =>
            ResourcesDeletionStatus(
              progress = Deleting,
              project = event.project,
              projectCreatedBy = resource.createdBy,
              projectCreatedAt = resource.createdAt,
              createdBy = resource.updatedBy,
              createdAt = resource.updatedAt,
              updatedAt = resource.updatedAt,
              uuid = uuid
            )
          )
    }

  private def buildStream: Stream[Task, Unit] =
    Stream
      .eval(projection.progress(projectionId))
      .evalTap { progress =>
        logger.info(s"Starting projects deletion stream")
        cache.putAll(progress.value.value)
      }
      .flatMap { initialProgress =>
        eventLog
          .eventsByTag(Projects.projectDeletedTag, initialProgress.offset)
          .map(_.toMessage)
          .evalMap { msg =>
            val project = msg.value.project
            logger.info(s"Starting deletion of project $project marked at ${msg.timestamp}")
            val uuid    = Projects.uuidFrom(project, msg.value.instant)
            Task
              .traverse(
                List(
                  resourcesDeletion.freeResources(project),
                  resourcesDeletion.deleteCaches(project),
                  resourcesDeletion.deleteRegistry(project)
                )
              )(deletionAction(msg.value, uuid, _))
              .map { statuses =>
                val progress = statuses.lastOption.fold(initialProgress.value) { s =>
                  initialProgress.value + (uuid -> s)
                }
                msg.as(progress)
              }
          }
          .persistProgress(initialProgress, projectionId, projection, persistProgressConfig)
          .enableMetrics
          .void
      }

}
