package ch.epfl.bluebrain.nexus.delta.plugins.statistics.indexing

import cats.syntax.functor._
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.{ElasticSearchClient, IndexLabel}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.indexing.ElasticSearchIndexingStreamEntry
import ch.epfl.bluebrain.nexus.delta.plugins.statistics.RelationshipResolution
import ch.epfl.bluebrain.nexus.delta.plugins.statistics.model.JsonLdPathValue.Metadata
import ch.epfl.bluebrain.nexus.delta.plugins.statistics.model.JsonLdPathValueCollection.{JsonLdProperties, JsonLdRelationships}
import ch.epfl.bluebrain.nexus.delta.plugins.statistics.model.{JsonLdPathValue, JsonLdPathValueCollection}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.RdfError
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.graph.Graph
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.EventExchange.EventExchangeValue
import ch.epfl.bluebrain.nexus.delta.sdk.ProgressesStatistics.ProgressesCache
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.views.indexing.IndexingStream.ProgressStrategy
import ch.epfl.bluebrain.nexus.delta.sdk.views.indexing.{IndexingSource, IndexingStream}
import ch.epfl.bluebrain.nexus.delta.sdk.views.model.{IndexingData, ViewIndex}
import ch.epfl.bluebrain.nexus.delta.sdk.views.syntax._
import ch.epfl.bluebrain.nexus.delta.sourcing.config.ExternalIndexingConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.ProjectionId.ViewProjectionId
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.ProjectionProgress.NoProgress
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.{Projection, ProjectionProgress}
import fs2.Stream
import io.circe.Json
import io.circe.syntax._
import monix.bio.{IO, Task, UIO}
import monix.execution.Scheduler

/**
  * Defines how to build a stream for statistics
  */
final class StatisticsIndexingStream(
    client: ElasticSearchClient,
    indexingSource: IndexingSource,
    cache: ProgressesCache,
    config: ExternalIndexingConfig,
    projection: Projection[Unit],
    relationshipResolution: RelationshipResolution
)(implicit cr: RemoteContextResolution, sc: Scheduler)
    extends IndexingStream[StatisticsView] {

  override def apply(
      view: ViewIndex[StatisticsView],
      strategy: IndexingStream.ProgressStrategy
  ): Stream[Task, Unit] = {
    val index = idx(view)
    Stream
      .eval {
        // Evaluates strategy and set/get the appropriate progress
        client.createIndex(index, Some(view.value.mapping), None) >> handleProgress(strategy, view.projectionId)
      }
      .flatMap { progress =>
        indexingSource(view.projectRef, progress.offset, view.resourceTag)
          .evalMapValue { eventExchangeValue =>
            // Creates a JsonLdPathValueCollection from the event exchange response
            fromEventExchange(view.projectRef, eventExchangeValue)
          }
          // TODO: Every time a NEW resource (rev = 1) is created, we also need to run the update-by-query-creation task
          // TODO: Every time an existing resource (rev > 1) is updated and the types change, we also need to run the update-by-update task
          .evalMapFilterValue { res =>
            res.index(index, includeMetadata = false, sourceAsText = false)
          }
          .runAsyncUnit { bulk =>
            // Pushes INDEX/DELETE Elasticsearch bulk operations
            IO.when(bulk.nonEmpty)(client.bulk(bulk))
          }
          .flatMap(Stream.chunk)
          .map(_.void)
          // Persist progress in cache and in primary store
          .persistProgressWithCache(
            progress,
            view.projectionId,
            projection,
            cache.put(view.projectionId, _),
            config.projection,
            config.cache
          )
          .viewMetrics(view, nxv + "Statistics")
          .map(_.value)
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

  private def fromEventExchange[A, M](
      project: ProjectRef,
      exchangedValue: EventExchangeValue[A, M]
  )(implicit cr: RemoteContextResolution): IO[RdfError, ElasticSearchIndexingStreamEntry] = {
    val res     = exchangedValue.value.resource
    val encoder = exchangedValue.value.encoder
    for {
      expanded          <- encoder.expand(res.value)
      pathProperties     = JsonLdProperties.fromJson(expanded)
      pathRelationships <- relationships(pathProperties.relationshipCandidates, project)
      paths              = JsonLdPathValueCollection(pathProperties, pathRelationships)
      types              = Json.obj(keywords.id -> res.id.asJson).addIfNonEmpty(keywords.tpe, res.types)
      source             = paths.asJson deepMerge types
      data               = IndexingData(res.id, res.deprecated, res.schema, res.types, Graph.empty, Graph.empty, source)
    } yield ElasticSearchIndexingStreamEntry(data)
  }

  private def relationships(candidates: Map[Iri, JsonLdPathValue], projectRef: ProjectRef): UIO[JsonLdRelationships] = {
    UIO
      .parTraverseN(parallelism = 10)(candidates.toSeq) { case (id, pathValue) =>
        relationshipResolution(projectRef, id).map { relationshipsOpt =>
          relationshipsOpt.map(relationship => pathValue.withMeta(Metadata(Some(relationship.id), relationship.types)))
        }
      }
      .map(list => JsonLdRelationships(list.flatten))

  }

}
