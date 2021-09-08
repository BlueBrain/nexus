package ch.epfl.bluebrain.nexus.delta.sdk.views.indexing

import akka.actor.typed.ActorSystem
import akka.persistence.query.Offset
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategy
import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategy.logError
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.sdk.cache.KeyValueStoreConfig
import ch.epfl.bluebrain.nexus.delta.sdk.model.Envelope
import ch.epfl.bluebrain.nexus.delta.sdk.model.Event.ProjectScopedEvent
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectsConfig
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.views.model.ProjectsEventsInstantCollection
import ch.epfl.bluebrain.nexus.delta.sourcing.config.SaveProgressConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.ProjectionId.CacheProjectionId
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.stream.DaemonStreamCoordinator
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.tracing.ProgressTracingConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.{Projection, SuccessMessage}
import com.typesafe.scalalogging.Logger
import fs2.Stream
import monix.bio.Task
import monix.execution.Scheduler

sealed trait IndexingStreamAwake

object IndexingStreamAwake {

  private val logger: Logger = Logger[IndexingStreamAwake.type]
  private type StreamFromOffset = Offset => Stream[Task, Envelope[ProjectScopedEvent]]
  private[indexing] val projectionId: CacheProjectionId = CacheProjectionId("IndexingStreamAwake")
  implicit val tracingConfig: ProgressTracingConfig     = ProgressTracingConfig(projectionId.value, Map.empty)

  /**
    * Construct a [[IndexingStreamAwake]] from a passed ''projection'' and ''stream'' function. The underlying stream
    * will store its progress and compute the latest event instant for each project.
    */
  def apply(
      config: ProjectsConfig,
      projection: Projection[ProjectsEventsInstantCollection],
      stream: StreamFromOffset,
      onNewEvent: OnEventInstant
  )(implicit uuidF: UUIDF, as: ActorSystem[Nothing], sc: Scheduler): Task[IndexingStreamAwake] = {
    implicit val keyValueStoreCfg: KeyValueStoreConfig  = config.keyValueStore
    implicit val persistProgressCfg: SaveProgressConfig = config.persistProgressConfig
    apply(projection, stream, onNewEvent).as(new IndexingStreamAwake {})
  }

  private[indexing] def apply(
      projection: Projection[ProjectsEventsInstantCollection],
      stream: StreamFromOffset,
      onEvent: OnEventInstant
  )(implicit
      uuidF: UUIDF,
      keyValueStoreConfig: KeyValueStoreConfig,
      persistProgressConfig: SaveProgressConfig,
      as: ActorSystem[Nothing],
      sc: Scheduler
  ): Task[Unit] = {

    def buildStream: Stream[Task, Unit] =
      Stream
        .eval(projection.progress(projectionId))
        .flatMap { progress =>
          val initial =
            SuccessMessage(progress.offset, progress.timestamp, "", 1, progress.value, Vector.empty)
          stream(progress.offset)
            .map { env => env.toMessage.as(env.event.project) }
            .evalScan(initial) { case (acc, msg) =>
              val evInstant = msg.timestamp
              val evProject = msg.value
              onEvent
                .awakeIndexingStream(evProject, acc.value.value.get(evProject), evInstant)
                .as(msg.as(ProjectsEventsInstantCollection(acc.value.value + (evProject -> evInstant))))
            }
            .persistProgress(progress, projectionId, projection, persistProgressConfig)
            .enableTracing
            .void
        }

    val retryStrategy =
      RetryStrategy[Throwable](keyValueStoreConfig.retry, _ => true, logError(logger, "IndexingStreamAwake"))

    DaemonStreamCoordinator.run("IndexingStreamAwake", buildStream, retryStrategy)
  }

}
