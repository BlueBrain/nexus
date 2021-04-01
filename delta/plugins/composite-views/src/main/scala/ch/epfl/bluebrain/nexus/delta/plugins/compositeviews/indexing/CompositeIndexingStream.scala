package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.indexing

import akka.persistence.query.{NoOffset, Offset}
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClasspathResourceUtils
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.BlazegraphViews
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.{BlazegraphClient, SparqlQuery}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.indexing.BlazegraphIndexingStreamEntry
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.indexing.CompositeIndexingStream._
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewProjection.{idTemplating, ElasticSearchProjection, SparqlProjection}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewSource.{CrossProjectSource, ProjectSource, RemoteProjectSource}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.{CompositeView, CompositeViewProjection, CompositeViewSource}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.ElasticSearchViews
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.{ElasticSearchClient, IndexLabel}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.indexing.ElasticSearchIndexingStreamEntry
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.{IndexingData => ElasticSearchIndexingData}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.ProgressesStatistics.ProgressesCache
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.views.indexing.IndexingStream.ProgressStrategy
import ch.epfl.bluebrain.nexus.delta.sdk.views.indexing.{IndexingSource, IndexingStream}
import ch.epfl.bluebrain.nexus.delta.sdk.views.model.ViewIndex
import ch.epfl.bluebrain.nexus.delta.sourcing.config.ExternalIndexingConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.ProjectionId.{CompositeViewProjectionId, SourceProjectionId}
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.ProjectionProgress.NoProgress
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.ProjectionStream._
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.instances._
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.{Message, Projection, ProjectionId, ProjectionProgress}
import fs2.{Chunk, Pipe, Stream}
import io.circe.Json
import monix.bio.{IO, Task}
import monix.execution.Scheduler

import java.util.regex.Pattern.quote
import scala.math.Ordering.Implicits._

/**
  * Defines how to build a stream for a CompositeView
  */
final class CompositeIndexingStream(
    esConfig: ExternalIndexingConfig,
    esClient: ElasticSearchClient,
    blazeConfig: ExternalIndexingConfig,
    blazeClient: BlazegraphClient,
    cache: ProgressesCache,
    projections: Projection[Unit],
    indexingSource: IndexingSource
)(implicit cr: RemoteContextResolution, baseUri: BaseUri, sc: Scheduler)
    extends IndexingStream[CompositeView] {

  implicit private val cl: ClassLoader = getClass.getClassLoader

  override def apply(view: ViewIndex[CompositeView], strategy: ProgressStrategy): Stream[Task, Unit] =
    Stream
      .eval {
        // evaluates strategy and set/get the appropriate progress
        createIndices(view) >>
          handleProgress(strategy, view)
      }
      .flatMap { progressMap =>
        val streams = view.value.sources.value.map { source =>
          val sourcePId = sourceProjection(source, view.rev)
          val progress  = progressMap(sourcePId)
          // fetches the source stream
          val stream    = source match {
            case s: ProjectSource       => indexingSource(view.projectRef, progress.offset, s.resourceTag)
            case s: CrossProjectSource  => indexingSource(s.project, progress.offset, s.resourceTag)
            case _: RemoteProjectSource => Stream.empty // TODO: To be implemented
          }
          // Converts the resource to a graph we are interested when indexing into the common blazegraph namespace
          stream
            .evalMapValue(BlazegraphIndexingStreamEntry.fromEventExchange(_))
            .evalMapFilterValue {
              // Either delete the named graph or insert triples to it depending on filtering options
              case res if res.containsSchema(source.resourceSchemas) && res.containsTypes(source.resourceTypes) =>
                res.deleteOrIndex(includeMetadata = true, source.includeDeprecated).map(q => Some(res -> q))
              case res if res.containsSchema(source.resourceSchemas)                                            =>
                res.delete().map(q => Some(res -> q))
              case _                                                                                            =>
                Task.none
            }
            .runAsyncUnit { bulkResource =>
              // Pushes DROP/REPLACE queries to Blazegraph common namespace
              IO.when(bulkResource.nonEmpty)(blazeClient.bulk(view.index, bulkResource.map(_._2)))
            }
            .mapValue { case (res, _) => res }
            .broadcastThrough(view.value.projections.value.map { projection =>
              val pId = projectionId(view, sourcePId, projection)
              projectionPipe(view, pId, projection, progressMap(pId).void)
            }.toSeq: _*)

        }
        streams.foldLeft[Stream[Task, Unit]](Stream.empty)(_ merge _)
      }

  private def projectionPipe(
      view: ViewIndex[CompositeView],
      pId: CompositeViewProjectionId,
      projection: CompositeViewProjection,
      progress: ProjectionProgress[Unit]
  ): Pipe[Task, Chunk[Message[BlazegraphIndexingStreamEntry]], Unit] = {
    val cfg = indexingConfig(projection)
    _.evalMapFilterValue {
      // Filters out the resources that are not to be indexed by the projections and the ones that are to be deleted
      case res if res.containsSchema(projection.resourceSchemas) && res.containsTypes(projection.resourceTypes) =>
        Task.pure(Some(res -> res.deleteCandidate(projection.includeDeprecated)))
      case res if res.containsSchema(projection.resourceSchemas)                                                =>
        Task.pure(Some(res -> true))
      case _                                                                                                    =>
        Task.none
    }.evalMapValue {
      case (BlazegraphIndexingStreamEntry(resource), deleteCandidate) if !deleteCandidate =>
        // Run projection query against common blazegraph namespace
        val query = SparqlQuery(projection.query.replaceAll(quote(idTemplating), s"<${resource.id}>"))
        for {
          queryResult    <- blazeClient.query(Set(view.index), query)
          graphResult    <- Task.fromEither(queryResult.asGraph)
          rootGraphResult = graphResult.replaceRootNode(resource.id)
          newResource     = resource.map(data => data.copy(graph = rootGraphResult))
        } yield BlazegraphIndexingStreamEntry(newResource) -> false

      case resDeleteCandidate                                                             => Task.pure(resDeleteCandidate)
    }.runAsyncUnit { list =>
      projection match {
        case p: ElasticSearchProjection =>
          list
            .traverse { case (BlazegraphIndexingStreamEntry(resource), deleteCandidate) =>
              val data  = ElasticSearchIndexingData(resource.value.graph, resource.value.metadataGraph, Json.obj())
              val esRes = ElasticSearchIndexingStreamEntry(resource.as(data))
              val index = idx(p, view.rev)
              if (deleteCandidate) esRes.delete(index)
              else esRes.index(index, p.includeMetadata, sourceAsText = false, p.context)
            }
            .flatMap { bulk =>
              // Pushes INDEX/DELETE Elasticsearch bulk operations
              IO.when(bulk.nonEmpty)(esClient.bulk(bulk))
            }

        case p: SparqlProjection =>
          list
            .traverse { case (res, deleteCandidate) =>
              if (deleteCandidate) res.delete()
              else res.index(p.includeMetadata)
            }
            .flatMap { bulk =>
              // Pushes DROP/REPLACE queries to Blazegraph
              IO.when(bulk.nonEmpty)(blazeClient.bulk(ns(p, view.rev), bulk))
            }
      }
    }.flatMap(Stream.chunk)
      .map(_.void)
      // Persist progress in cache and in primary store
      .persistProgressWithCache(
        progress,
        pId,
        projections,
        cache.put(pId, _),
        cfg.projection,
        cfg.cache
      )
  }

  private def createIndices(view: ViewIndex[CompositeView]): Task[Unit] =
    for {
      props <- ClasspathResourceUtils.ioPropertiesOf("blazegraph/index.properties")
      _     <- blazeClient.createNamespace(view.index, props) // common blazegraph namespace
      _     <- Task.traverse(view.value.projections.value) {
                 case p: ElasticSearchProjection =>
                   esClient.createIndex(idx(p, view.rev), Some(p.mapping), p.settings).void
                 case p: SparqlProjection        =>
                   blazeClient.createNamespace(ns(p, view.rev), props).void
               }
    } yield ()

  private def handleProgress(
      strategy: ProgressStrategy,
      view: ViewIndex[CompositeView]
  ): Task[Map[ProjectionId, ProjectionProgress[SkipIndexingUntil]]] = {

    def collect(list: List[ProjectionProgress[CompositeViewProjectionId]]) =
      list.foldLeft(Map.empty[ProjectionId, ProjectionProgress[SkipIndexingUntil]]) { case (acc, progress) =>
        val newProgress    = progress.as(noSkipIndexing)
        val sourceProgress = acc
          .get(progress.value.sourceId)
          .collect { case prev if newProgress.offset > prev.offset => prev }
          .getOrElse(newProgress)
        acc + (progress.value -> newProgress) + (progress.value.sourceId -> sourceProgress)
      }

    def reset(projectionId: CompositeViewProjectionId) =
      cache.remove(projectionId) >>
        cache.put(projectionId, NoProgress) >>
        projections.recordProgress(projectionId, NoProgress).as(NoProgress(projectionId))

    strategy match {
      case ProgressStrategy.Continue    =>
        IO.traverse(projectionIds(view))(pId => projections.progress(pId).map(_.as(pId))).map(collect)
      case ProgressStrategy.FullRestart =>
        IO.traverse(projectionIds(view))(pId => reset(pId)).map(collect)
      case PartialRestart(toRestart)    =>
        IO.traverse(projectionIds(view)) {
          case pId if toRestart.contains(pId) =>
            projections.progress(pId).flatMap { progress =>
              reset(pId).map(reset => (reset, reset.map(_.sourceId -> SkipIndexingUntil(progress.offset))))
            }
          case pId                            =>
            projections.progress(pId).map { progress =>
              (progress.as(pId), progress.as(pId.sourceId -> noSkipIndexing))
            }
        }.map(_.foldLeft(Map.empty[ProjectionId, ProjectionProgress[SkipIndexingUntil]]) {
          case (acc, (pProgress, sProgress)) =>
            val (sId, skipIndexingUntil) = sProgress.value
            val sourceProgress           = acc
              .get(sId)
              .map {
                case prev if prev.offset >= sProgress.offset =>
                  sProgress.as(SkipIndexingUntil(prev.value.offset.max(skipIndexingUntil.offset)))
                case prev                                    =>
                  prev.as(SkipIndexingUntil(prev.value.offset.max(skipIndexingUntil.offset)))
              }
              .getOrElse(sProgress.as(skipIndexingUntil))
            acc + (pProgress.value -> pProgress.as(noSkipIndexing)) + (sId -> sourceProgress)
        })
    }
  }

  private def idx(projection: ElasticSearchProjection, rev: Long): IndexLabel =
    IndexLabel.unsafe(ElasticSearchViews.index(projection.uuid, rev, esConfig))

  private def ns(projection: SparqlProjection, rev: Long): String =
    BlazegraphViews.index(projection.uuid, rev, blazeConfig)

  private def sourceProjection(source: CompositeViewSource, rev: Long) =
    SourceProjectionId(s"${source.uuid}_$rev")

  private def projectionIds(view: ViewIndex[CompositeView]) =
    for {
      s <- view.value.sources.value
      p <- view.value.projections.value
    } yield projectionId(view, sourceProjection(s, view.rev), p)

  private def projectionId(
      view: ViewIndex[CompositeView],
      sourceId: SourceProjectionId,
      projection: CompositeViewProjection
  ) =
    projection match {
      case p: ElasticSearchProjection =>
        CompositeViewProjectionId(sourceId, ElasticSearchViews.projectionId(p.uuid, view.rev))
      case p: SparqlProjection        =>
        CompositeViewProjectionId(sourceId, BlazegraphViews.projectionId(p.uuid, view.rev))
    }

  private def indexingConfig(projection: CompositeViewProjection): ExternalIndexingConfig =
    projection match {
      case _: ElasticSearchProjection => esConfig
      case _: SparqlProjection        => blazeConfig
    }

}

private[indexing] object CompositeIndexingStream {
  final case class PartialRestart(projectionIds: Set[CompositeViewProjectionId]) extends ProgressStrategy
  final case class SkipIndexingUntil(offset: Offset)
  val noSkipIndexing: SkipIndexingUntil = SkipIndexingUntil(NoOffset)

}
