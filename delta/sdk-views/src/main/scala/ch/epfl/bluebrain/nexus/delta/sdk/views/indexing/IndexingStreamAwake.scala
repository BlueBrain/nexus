package ch.epfl.bluebrain.nexus.delta.sdk.views.indexing

import akka.actor.typed.ActorSystem
import akka.persistence.query.Offset
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategy.logError
import ch.epfl.bluebrain.nexus.delta.kernel.kamon.KamonMetricsConfig
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.kernel.{RetryStrategy, RetryStrategyConfig}
import ch.epfl.bluebrain.nexus.delta.sdk.model.Envelope
import ch.epfl.bluebrain.nexus.delta.sdk.model.Event.ProjectScopedEvent
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.views.model.ProjectsEventsInstantCollection
import ch.epfl.bluebrain.nexus.delta.sourcing.config.SaveProgressConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.ProjectionId.CacheProjectionId
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.stream.DaemonStreamCoordinator
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.{Projection, SuccessMessage}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import com.typesafe.scalalogging.Logger
import fs2.Stream
import fs2.concurrent.Queue
import monix.bio.{Task, UIO}
import monix.execution.Scheduler

sealed trait IndexingStreamAwake {

  /**
    * Remove the project from the project that should be handled
    *
    * @param project
    *   the project to remove
    */
  def remove(project: ProjectRef): UIO[Unit]
}

object IndexingStreamAwake {

  private val logger: Logger = Logger[IndexingStreamAwake.type]
  private type StreamFromOffset = Offset => Stream[Task, Envelope[ProjectScopedEvent]]
  private[indexing] val projectionId: CacheProjectionId  = CacheProjectionId("IndexingStreamAwake")
  implicit private val metricsConfig: KamonMetricsConfig = KamonMetricsConfig(projectionId.value, Map.empty)

  /**
    * Construct a [[IndexingStreamAwake]] from a passed ''projection'' and ''stream'' function. The underlying stream
    * will store its progress and compute the latest event instant for each project.
    */
  def apply(
      projection: Projection[ProjectsEventsInstantCollection],
      stream: StreamFromOffset,
      onEvent: OnEventInstant,
      retry: RetryStrategyConfig,
      persistProgress: SaveProgressConfig
  )(implicit uuidF: UUIDF, as: ActorSystem[Nothing], sc: Scheduler): Task[IndexingStreamAwake] = {
    Queue.unbounded[Task, ProjectRef].flatMap { queue =>
      def buildStream: Stream[Task, Unit] =
        Stream
          .eval(projection.progress(projectionId))
          .flatMap { progress =>
            val initial =
              SuccessMessage(progress.offset, progress.timestamp, "", 1, progress.value, Vector.empty)
            Stream(stream(progress.offset).map(Right(_)), queue.dequeue.map(Left(_))).parJoinUnbounded
              .evalScan(initial) { case (acc, current) =>
                current.fold(
                  project => Task.pure(acc.copy(value = acc.value - project)),
                  envelope => {
                    val event = envelope.event
                    val value = acc.value upsert (event.project, envelope.instant)
                    onEvent
                      .awakeIndexingStream(event.project, acc.value.value.get(event.project), envelope.instant)
                      .as(envelope.toMessage.copy(value = value))
                  }
                )
              }
              .persistProgress(progress, projectionId, projection, persistProgress)
              .enableMetrics
              .void
          }

      val retryStrategy =
        RetryStrategy[Throwable](retry, _ => true, logError(logger, "IndexingStreamAwake"))

      DaemonStreamCoordinator
        .run("IndexingStreamAwake", buildStream, retryStrategy)
        .as(
          new IndexingStreamAwake {
            override def remove(project: ProjectRef): UIO[Unit] = queue.enqueue1(project).hideErrors
          }
        )
    }
  }

}
