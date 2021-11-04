package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.indexing

import cats.syntax.functor._
import ch.epfl.bluebrain.nexus.delta.kernel.kamon.KamonMetricsConfig
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.{ElasticSearchBulk, ElasticSearchClient, IndexLabel}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.config.ElasticSearchViewsConfig
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchView.IndexingElasticSearchView
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.EventExchange.EventExchangeValue
import ch.epfl.bluebrain.nexus.delta.sdk.ProgressesStatistics.ProgressesCache
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.views.indexing.IndexingStream.ProgressStrategy
import ch.epfl.bluebrain.nexus.delta.sdk.views.indexing.{IndexingSource, IndexingStream}
import ch.epfl.bluebrain.nexus.delta.sdk.views.model.{IndexingData, ViewIndex}
import ch.epfl.bluebrain.nexus.delta.sdk.views.pipe.Pipe.PipeResult
import ch.epfl.bluebrain.nexus.delta.sdk.views.pipe.{Pipe, PipeConfig}
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.ProjectionId.ViewProjectionId
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.ProjectionProgress.NoProgress
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.{Projection, ProjectionProgress}
import fs2.Stream
import io.circe.Json
import monix.bio.{IO, Task}
import monix.execution.Scheduler

/**
  * Defines how to build a stream for an IndexingElasticSearchView
  */
final class ElasticSearchIndexingStream(
    client: ElasticSearchClient,
    indexingSource: IndexingSource,
    cache: ProgressesCache,
    pipeConfig: PipeConfig,
    config: ElasticSearchViewsConfig,
    projection: Projection[Unit]
)(implicit cr: RemoteContextResolution, baseUri: BaseUri, sc: Scheduler)
    extends IndexingStream[IndexingElasticSearchView] {

  override def apply(
      view: ViewIndex[IndexingElasticSearchView],
      strategy: IndexingStream.ProgressStrategy
  ): Task[Stream[Task, Unit]] = {
    implicit val metricsConfig: KamonMetricsConfig = ViewIndex.metricsConfig(view, view.value.tpe.tpe)
    val index                                      = idx(view)

    def encoder = DataEncoder.defaultEncoder(view.value.context)
    Pipe.run(view.value.pipeline, pipeConfig).map { pipeline =>
      Stream
        .eval {
          // Evaluates strategy and set/get the appropriate progress
          client.createIndex(index, Some(view.value.mapping), Some(view.value.settings)) >>
            handleProgress(strategy, view.projectionId)
        }
        .flatMap { progress =>
          indexingSource(view.projectRef, progress.offset, view.resourceTag)
            .evalMapFilterValue(ElasticSearchIndexingStream.process(_, index, pipeline, encoder))
            .runAsyncUnit { bulk =>
              // Pushes INDEX/DELETE Elasticsearch bulk operations
              IO.when(bulk.nonEmpty)(client.bulk(bulk))
            }
            .flatMap(Stream.chunk)
            .filterValue {
              case _: ElasticSearchBulk.Delete => false
              case _                           => true
            }
            .map(_.void)
            // Persist progress in cache and in primary store
            .persistProgressWithCache(
              progress,
              view.projectionId,
              projection,
              cache.put(view.projectionId, _),
              config.indexing.projection,
              config.indexing.cache
            )
            .enableMetrics
            .map(_.value)
        }
    }

  }

  private def handleProgress(
      strategy: ProgressStrategy,
      projectionId: ViewProjectionId
  ): Task[ProjectionProgress[Unit]] =
    strategy match {
      case ProgressStrategy.Continue    =>
        for {
          progress <- projection.progress(projectionId)
          _        <- cache.put(projectionId, progress)
        } yield progress
      case ProgressStrategy.FullRestart =>
        cache.remove(projectionId) >>
          cache.put(projectionId, NoProgress) >>
          projection.recordProgress(projectionId, NoProgress).as(NoProgress)
    }

  private def idx(view: ViewIndex[_]): IndexLabel =
    IndexLabel.unsafe(view.index)
}

object ElasticSearchIndexingStream {

  /**
    * Process the event exchange value to get a Elasticsearch bulk entry
    */
  def process(
      eventExchangeValue: EventExchangeValue[_, _],
      index: IndexLabel,
      pipeline: IndexingData => PipeResult,
      dataEncoder: IndexingData => Task[Json]
  )(implicit cr: RemoteContextResolution, baseUri: BaseUri): IO[Throwable, Option[ElasticSearchBulk]] =
    for {
      data   <- IndexingData(eventExchangeValue)
      result <- pipeline(data)
      bulk   <- result match {
                  case None =>
                    Task.some(ElasticSearchBulk.Delete(index, data.id.toString)) // TODO skip delete if rev = 1 ?
                  case Some(r) =>
                    dataEncoder(r).flatMap {
                      case json if json.isEmpty() => Task.none // TODO Delete ?
                      case json                   =>
                        Task.some(
                          ElasticSearchBulk.Index(index, data.id.toString, json)
                        )
                    }
                }
    } yield bulk

}
