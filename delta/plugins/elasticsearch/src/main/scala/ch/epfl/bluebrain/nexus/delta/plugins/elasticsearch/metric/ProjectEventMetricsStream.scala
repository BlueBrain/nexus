package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.metric

import akka.actor.typed.ActorSystem
import akka.persistence.query.Offset
import cats.syntax.functor._
import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategy
import ch.epfl.bluebrain.nexus.delta.kernel.utils.{ClasspathResourceUtils, UUIDF}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.{ElasticSearchBulk, ElasticSearchClient, IndexLabel}
import ch.epfl.bluebrain.nexus.delta.sdk.EventExchange
import ch.epfl.bluebrain.nexus.delta.sdk.model.Envelope
import ch.epfl.bluebrain.nexus.delta.sdk.model.Event.ProjectScopedEvent
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sourcing.config.ExternalIndexingConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.Projection
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.ProjectionId.CacheProjectionId
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.stream.DaemonStreamCoordinator
import com.typesafe.scalalogging.Logger
import fs2.Stream
import io.circe.JsonObject
import io.circe.syntax.EncoderOps
import monix.bio.{Task, UIO}
import monix.execution.Scheduler
import retry.syntax.all._

sealed trait ProjectEventMetricsStream

object ProjectEventMetricsStream {

  implicit private val logger: Logger  = Logger("ProjectEventMetricsStream")
  implicit private val cl: ClassLoader = getClass.getClassLoader

  private type ProjectEventStreamFromOffset = Offset => Stream[Task, Envelope[ProjectScopedEvent]]
  val projectionId: CacheProjectionId = CacheProjectionId("ProjectScopedMetrics")

  /**
    * elasticsearch mapping for metrics
    */
  private val metricsMapping: UIO[JsonObject] = ClasspathResourceUtils
    .ioJsonObjectContentOf("metrics/metrics-mapping.json")
    .logAndDiscardErrors("loading metrics mapping")
    .memoize

  /**
    * Default elasticsearch settings for metrics
    */
  private val metricsSettings: UIO[JsonObject] = ClasspathResourceUtils
    .ioJsonObjectContentOf("metrics/metrics-settings.json")
    .logAndDiscardErrors("loading metrics settings")
    .memoize

  def apply(
      stream: ProjectEventStreamFromOffset,
      exchanges: Set[EventExchange],
      client: ElasticSearchClient,
      projection: Projection[Unit],
      config: ExternalIndexingConfig
  )(implicit uuidF: UUIDF, as: ActorSystem[Nothing], sc: Scheduler): Task[ProjectEventMetricsStream] = {
    val retryStrategy                   = RetryStrategy.retryOnNonFatal(config.retry, logger, "p stream")
    val exchangesList                   = exchanges.toList
    val index                           = IndexLabel.unsafe(s"${config.prefix}_project_metrics")
    def buildStream: Stream[Task, Unit] = Stream
      .eval(
        for {
          mapping  <- metricsMapping
          settings <- metricsSettings
          _        <- client.createIndex(index, Some(mapping), Some(settings))
          progress <- projection.progress(projectionId)
        } yield progress
      )
      .flatMap { progress =>
        stream(progress.offset)
          .map(_.toMessage)
          .groupWithin(config.maxBatchSize, config.maxTimeWindow)
          .evalMapFilterValue { event =>
            Task.tailRecM(exchangesList) { // try all event exchanges one at a time until there's a result
              case Nil              => Task.pure(Right(None))
              case exchange :: rest =>
                exchange
                  .toMetric(event)
                  .map(_.toRight(rest).map(Some.apply))
                  .absorb
                  .retryingOnSomeErrors(retryStrategy.retryWhen, retryStrategy.policy, retryStrategy.onError)
            }
          }
          .runAsyncUnit { metrics =>
            client
              .bulk(
                metrics.map { e =>
                  ElasticSearchBulk.Index(index, e.eventId, e.asJson)
                }
              )
          }
          .map(_.map(_.void))
          .persistProgress(progress, projectionId, projection)
          .void
      }

    DaemonStreamCoordinator
      .run("ProjectScopedMetrics", buildStream, retryStrategy)
  }.as(new ProjectEventMetricsStream {})

}
