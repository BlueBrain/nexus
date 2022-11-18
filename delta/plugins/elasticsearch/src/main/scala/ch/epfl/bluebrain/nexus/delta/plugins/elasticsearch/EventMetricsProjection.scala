package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch

import ch.epfl.bluebrain.nexus.delta.kernel.database.Transactors
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.{ElasticSearchClient, IndexLabel}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.indexing.ElasticSearchSink
import ch.epfl.bluebrain.nexus.delta.sdk.model.metrics.EventMetric._
import ch.epfl.bluebrain.nexus.delta.sdk.model.metrics.ScopedEventMetricEncoder
import ch.epfl.bluebrain.nexus.delta.sourcing.config.{BatchConfig, QueryConfig}
import ch.epfl.bluebrain.nexus.delta.sourcing.event.EventStreaming
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem.SuccessElem
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.{CompiledProjection, ExecutionStrategy, ProjectionMetadata, Supervisor}
import ch.epfl.bluebrain.nexus.delta.sourcing.{MultiDecoder, Predicate}
import io.circe.syntax.EncoderOps
import monix.bio.Task

import scala.concurrent.duration.DurationInt

trait EventMetricsProjection

object EventMetricsProjection {

  private val projectionMetadata = ProjectionMetadata("system", "eventMetrics", None, None)
  private val eventMetricsIndex  = IndexLabel.unsafe("eventMetricsIndex")

  /**
    * Registers a projection with the supervisor which reads all scoped events and pushes their metrics to
    * Elasticsearch. Events of implementations of ScopedEvents that do not have an instance of ScopedEventMetricEncoder
    * are silently ignored.
    *
    * @param metricEncoders
    *   a set of encoders for all entit
    * @param queryConfig
    *   query config for fetching scoped events
    * @param supervisor
    *   the supervisor which will supervise the projection
    * @param client
    *   the elasticsearch client
    * @param xas
    *   doobie transactors
    * @return
    */
  def apply(
      metricEncoders: Set[ScopedEventMetricEncoder[_]],
      queryConfig: QueryConfig,
      supervisor: Supervisor,
      client: ElasticSearchClient,
      xas: Transactors,
      batchConfig: BatchConfig
  ): Task[EventMetricsProjection] = {

    val sink = new ElasticSearchSink(client, batchConfig.maxElements, batchConfig.maxInterval, eventMetricsIndex)

    val allEntityTypes = metricEncoders.map(_.entityType).toList

    implicit val multiDecoder: MultiDecoder[ProjectScopedMetric] =
      MultiDecoder(metricEncoders.map { encoder => encoder.entityType -> encoder.toMetric }.toMap)

    val pushScopedEventMetricsToSink                             =
      (offset: Offset) =>
        EventStreaming
          .fetchScoped(Predicate.root, allEntityTypes, offset, queryConfig, xas)
          .map { envelope =>
            val eventMetric = envelope.value
            SuccessElem(
              envelope.tpe,
              eventMetric.resourceId,
              Some(eventMetric.project),
              eventMetric.instant,
              envelope.offset,
              eventMetric.asJson,
              eventMetric.rev
            )
          }
          .chunks
          .evalTap {
            sink.apply
          }
          .drain

    val compiledProjection = CompiledProjection
      .fromStream(projectionMetadata, ExecutionStrategy.EveryNode, pushScopedEventMetricsToSink)

    // create the ES index before running the projection
    val init: Task[Unit] = client.createIndex(eventMetricsIndex, None, None).void

    supervisor
      .run(compiledProjection, init)
      .as(new EventMetricsProjection {})
  }

}
