package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch

import cats.data.NonEmptyChain
import ch.epfl.bluebrain.nexus.delta.kernel.database.Transactors
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.{ElasticSearchClient, IndexLabel}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.indexing.ElasticSearchSink
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.{metricsMapping, metricsSettings}
import ch.epfl.bluebrain.nexus.delta.sdk.model.metrics.EventMetric._
import ch.epfl.bluebrain.nexus.delta.sdk.model.metrics.ScopedEventMetricEncoder
import ch.epfl.bluebrain.nexus.delta.sourcing.config.{BatchConfig, QueryConfig}
import ch.epfl.bluebrain.nexus.delta.sourcing.event.EventStreaming
import ch.epfl.bluebrain.nexus.delta.sourcing.model.EnvelopeStream
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem.SuccessElem
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Operation.Sink
import ch.epfl.bluebrain.nexus.delta.sourcing.stream._
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.pipes.AsJson
import ch.epfl.bluebrain.nexus.delta.sourcing.{MultiDecoder, Predicate}
import monix.bio.Task

trait EventMetricsProjection

object EventMetricsProjection {
  val projectionMetadata: ProjectionMetadata = ProjectionMetadata("system", "event-metrics", None, None)
  private val eventMetricsIndex              = IndexLabel.unsafe("event_metrics_index")

  /**
    * @param metricEncoders
    *   a set of encoders for all entit
    * @param supervisor
    *   the supervisor which will supervise the projection
    * @param client
    *   the elasticsearch client
    * @param xas
    *   doobie transactors
    * @param batchConfig
    *   Elasticsearch batch config
    * @param queryConfig
    *   query config for fetching scoped events
    * @return
    *   a Task that registers a projection with the supervisor which reads all scoped events and pushes their metrics to
    *   Elasticsearch. Events of implementations of ScopedEvents that do not have an instance of
    *   ScopedEventMetricEncoder are silently ignored.
    */
  def apply(
      metricEncoders: Set[ScopedEventMetricEncoder[_]],
      supervisor: Supervisor,
      client: ElasticSearchClient,
      xas: Transactors,
      batchConfig: BatchConfig,
      queryConfig: QueryConfig
  ): Task[EventMetricsProjection] = {
    val allEntityTypes = metricEncoders.map(_.entityType).toList

    implicit val multiDecoder: MultiDecoder[ProjectScopedMetric] =
      MultiDecoder(metricEncoders.map { encoder => encoder.entityType -> encoder.toMetric }.toMap)

    // define how to get metrics from a given offset
    val metrics                                                  = (offset: Offset) =>
      EventStreaming.fetchScoped(Predicate.root, allEntityTypes, offset, queryConfig, xas)

    lazy val sink =
      new ElasticSearchSink(client, batchConfig.maxElements, batchConfig.maxInterval, eventMetricsIndex)

    // create the ES index before running the projection
    val init = for {
      mappings <- metricsMapping
      settings <- metricsSettings
      _        <- client.createIndex(eventMetricsIndex, Some(mappings), Some(settings))
    } yield ()

    apply(sink, supervisor, metrics, init)
  }

  /**
    * Test friendly apply method
    */
  def apply(
      sink: Sink,
      supervisor: Supervisor,
      metrics: Offset => EnvelopeStream[String, ProjectScopedMetric],
      init: Task[Unit]
  ): Task[EventMetricsProjection] = {

    val source = Source { (offset: Offset) =>
      metrics(offset)
        .map { envelope =>
          val eventMetric = envelope.value
          SuccessElem(
            envelope.tpe,
            eventMetric.resourceId,
            Some(eventMetric.project),
            eventMetric.instant,
            envelope.offset,
            eventMetric,
            eventMetric.rev
          )
        }
    }

    val compiledProjection =
      CompiledProjection.compile(
        projectionMetadata,
        ExecutionStrategy.PersistentSingleNode,
        source,
        NonEmptyChain.one(AsJson.pipe[ProjectScopedMetric]),
        sink
      )

    for {
      projection <- Task.fromEither(compiledProjection)
      _          <- supervisor.run(projection, init)
    } yield new EventMetricsProjection {}
  }

}
