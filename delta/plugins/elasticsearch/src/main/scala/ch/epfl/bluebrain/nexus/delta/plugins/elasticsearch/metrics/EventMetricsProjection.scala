package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.metrics

import cats.data.NonEmptyChain
import cats.effect.IO
import cats.effect.std.Env
import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.ElasticSearchClient
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.ElasticSearchClient.Refresh
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.indexing.ElasticSearchSink
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.{MetricsMapping, MetricsSettings}
import ch.epfl.bluebrain.nexus.delta.sdk.model.metrics.EventMetric._
import ch.epfl.bluebrain.nexus.delta.sdk.model.metrics.ScopedEventMetricEncoder
import ch.epfl.bluebrain.nexus.delta.sourcing.config.{BatchConfig, QueryConfig}
import ch.epfl.bluebrain.nexus.delta.sourcing.event.EventStreaming
import ch.epfl.bluebrain.nexus.delta.sourcing.model.SuccessElemStream
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.Projections
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Operation.Sink
import ch.epfl.bluebrain.nexus.delta.sourcing.stream._
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.pipes.AsJson
import ch.epfl.bluebrain.nexus.delta.sourcing.{MultiDecoder, Scope, Transactors}

trait EventMetricsProjection

object EventMetricsProjection {
  private val logger = Logger[EventMetricsProjection]

  val projectionMetadata: ProjectionMetadata = ProjectionMetadata("system", "event-metrics", None, None)

  // We need a value to return to Distage
  private val dummy = new EventMetricsProjection {}

  /**
    * @param metricEncoders
    *   a set of encoders for all entity
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
    * @param indexPrefix
    *   the prefix to use for the index name
    * @return
    *   a Task that registers a projection with the supervisor which reads all scoped events and pushes their metrics to
    *   Elasticsearch. Events of implementations of ScopedEvents that do not have an instance of
    *   ScopedEventMetricEncoder are silently ignored.
    */
  def apply(
      metricEncoders: Set[ScopedEventMetricEncoder[_]],
      supervisor: Supervisor,
      projections: Projections,
      client: ElasticSearchClient,
      xas: Transactors,
      batchConfig: BatchConfig,
      queryConfig: QueryConfig,
      indexPrefix: String,
      metricMappings: MetricsMapping,
      metricsSettings: MetricsSettings,
      indexingEnabled: Boolean
  ): IO[EventMetricsProjection] = if (indexingEnabled) {
    val allEntityTypes = metricEncoders.map(_.entityType).toList

    implicit val multiDecoder: MultiDecoder[ProjectScopedMetric] =
      MultiDecoder(metricEncoders.map { encoder => encoder.entityType -> encoder.toMetric }.toMap)

    // define how to get metrics from a given offset
    val metrics                                                  = (offset: Offset) => EventStreaming.fetchScoped(Scope.root, allEntityTypes, offset, queryConfig, xas)

    val index = eventMetricsIndex(indexPrefix)

    val sink =
      ElasticSearchSink.events(client, batchConfig.maxElements, batchConfig.maxInterval, index, Refresh.False)

    val createIndex = client.createIndex(index, Some(metricMappings.value), Some(metricsSettings.value)).void

    for {
      shouldRestart     <- Env[IO].get("RESET_EVENT_METRICS").map(_.getOrElse("false").toBoolean)
      _                 <- IO.whenA(shouldRestart)(
                             logger.warn("Resetting event metrics as the env RESET_EVENT_METRICS is set...") >>
                               client.deleteIndex(index) >>
                               projections.reset(projectionMetadata.name)
                           )
      metricsProjection <- apply(sink, supervisor, metrics, createIndex)
    } yield metricsProjection

  } else IO.pure(dummy)

  /**
    * Test friendly apply method
    */
  def apply(
      sink: Sink,
      supervisor: Supervisor,
      metrics: Offset => SuccessElemStream[ProjectScopedMetric],
      init: IO[Unit]
  ): IO[EventMetricsProjection] = {

    val source = Source { (offset: Offset) => metrics(offset) }

    val compiledProjection =
      CompiledProjection.compile(
        projectionMetadata,
        ExecutionStrategy.PersistentSingleNode,
        source,
        NonEmptyChain(AsJson.pipe[ProjectScopedMetric]),
        sink
      )

    for {
      projection <- IO.fromEither(compiledProjection)
      _          <- supervisor.run(projection, init)
    } yield dummy
  }

}
