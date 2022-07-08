package ch.epfl.bluebrain.nexus.delta.plugins.graph.analytics.indexing

import akka.persistence.query.Offset
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.kernel.kamon.KamonMetricsConfig
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.ElasticSearchClient.Refresh
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.{ElasticSearchBulk, ElasticSearchClient, IndexLabel}
import ch.epfl.bluebrain.nexus.delta.plugins.graph.analytics.ResourceParser
import ch.epfl.bluebrain.nexus.delta.plugins.graph.analytics.indexing.GraphAnalyticsIndexingStream.GraphElement.{NewFileElement, ResourceElement}
import ch.epfl.bluebrain.nexus.delta.plugins.graph.analytics.indexing.GraphAnalyticsIndexingStream.{EventStream, GraphElement}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileEvent.FileCreated
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.nxvFile
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sdk.ProgressesStatistics.ProgressesCache
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Envelope, Event}
import ch.epfl.bluebrain.nexus.delta.sdk.projects.Projects
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.views.indexing.IndexingStream
import ch.epfl.bluebrain.nexus.delta.sdk.views.indexing.IndexingStream.ProgressStrategy
import ch.epfl.bluebrain.nexus.delta.sdk.views.model.ViewIndex
import ch.epfl.bluebrain.nexus.delta.sourcing.EventLog
import ch.epfl.bluebrain.nexus.delta.sourcing.config.ExternalIndexingConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.ProjectionId.ViewProjectionId
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.ProjectionProgress.NoProgress
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.{Projection, ProjectionProgress, SuccessMessage}
import fs2.Stream
import io.circe.JsonObject
import io.circe.literal._
import io.circe.syntax._
import monix.bio.{IO, Task, UIO}
import monix.execution.Scheduler

/**
  * Defines how to build a stream for graph analytics
  */
final class GraphAnalyticsIndexingStream(
    client: ElasticSearchClient,
    buildStream: (ProjectRef, Offset) => UIO[EventStream],
    resourceParser: ResourceParser,
    cache: ProgressesCache,
    config: ExternalIndexingConfig,
    projection: Projection[Unit]
)(implicit sc: Scheduler)
    extends IndexingStream[GraphAnalyticsView] {

  @SuppressWarnings(Array("OptionGet"))
  private def relationshipsQuery(resources: Map[Iri, Set[Iri]]): JsonObject = {
    val terms = resources.map { case (id, _) => id.asJson }.asJson
    json"""
    {
      "query": {
        "bool": {
          "filter": {
            "terms": {
              "references.@id": $terms
            }
          }
        }
      },
      "script": {
        "id": "updateRelationships",
        "params": {
          "resources": $resources
        }
      }
    }
    """.asObject.get
  }

  override def apply(
      view: ViewIndex[GraphAnalyticsView],
      strategy: IndexingStream.ProgressStrategy
  ): Task[Stream[Task, Unit]] = Task.delay {
    implicit val metricsConfig: KamonMetricsConfig = ViewIndex.metricsConfig(view, nxv + "GraphAnalytics")
    val index                                      = idx(view)
    Stream
      .eval {
        // Evaluates strategy and set/get the appropriate progress
        for {
          _        <- client.createIndex(index, Some(view.value.mapping), None)
          progress <- handleProgress(strategy, view.projectionId)
          stream   <- buildStream(view.projectRef, progress.offset)
        } yield (progress, stream)
      }
      .flatMap { case (progress, stream) =>
        stream
          .map(_.toMessage)
          .collectSomeValue[GraphElement] {
            // To update potential relationships pointing to this file
            case f: FileCreated => Some(NewFileElement(f.id))
            // To create / update the resource in the graph and update potential relationships pointing to it
            // TODO: Uncomment when migrating graph-analytics plugin
            //            case c: ResourceCreated => Some(ResourceElement(c.id, c.rev))
            //            case u: ResourceUpdated => Some(ResourceElement(u.id, u.rev))
            // We don't care about other events
            case _              => None
          }
          .groupWithin(config.maxBatchSize, config.maxTimeWindow)
          .evalMap { chunk =>
            val resourceById = chunk.foldLeft(Map.empty[String, Long]) {
              case (acc, SuccessMessage(_, _, _, _, ResourceElement(id, rev), _, _)) => acc.updated(id.toString, rev)
              case (acc, _)                                                          => acc
            }
            client.multiGet[Long](index, resourceById.keySet, "_rev").map { indexedRevisions =>
              // We discard if there is already a up-to-date version in Elasticsearch
              // Or there is already the same id with a greater rev in the current chunk
              def discardCond(resourceId: Iri, rev: Long) = {
                indexedRevisions.exists { case (id, maxRev) =>
                  id == resourceId.toString && maxRev.exists(_ >= rev)
                } ||
                resourceById.exists { case (id, maxRev) =>
                  id == resourceId.toString && maxRev > rev
                }
              }
              chunk.map { m =>
                m.mapFilter {
                  case r: ResourceElement if discardCond(r.id, r.rev) =>
                    None
                  case other                                          => Some(other)
                }
              }
            }
          }
          .runAsyncUnit { list =>
            IO.when(list.nonEmpty) {
              list
                .foldLeftM((Map.empty[Iri, Set[Iri]], List.empty[ElasticSearchBulk])) {
                  case ((typesMap, bulkList), file: NewFileElement)      =>
                    // If it is a file, we just isse a update by query
                    UIO.pure((typesMap + (file.id -> Set(nxvFile)), bulkList))
                  case ((typesMap, bulkList), resource: ResourceElement) =>
                    resourceParser(resource.id, view.projectRef).map {
                      case Some(result) =>
                        // If it is a resource, we both index and issue an update by query
                        (
                          typesMap + (result.id -> result.types),
                          ElasticSearchBulk.Index(
                            index,
                            resource.id.toString,
                            result.asJson
                          ) :: bulkList
                        )
                      case None         => (typesMap, bulkList)
                    }
                }
                .flatMap { case (idTypesMap, bulkOps) =>
                  // Pushes INDEX Elasticsearch bulk operations & performs an update by query
                  client.bulk(bulkOps, Refresh.True) >>
                    client.updateByQuery(relationshipsQuery(idTypesMap), Set(index.value))
                }
            }
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
          .enableMetrics
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
      case _                            =>
        Task.raiseError(
          new IllegalArgumentException(
            "Only `Continue` and `FullRestart` are valid progress strategies for graph-analytics."
          )
        )
    }

  private def idx(view: ViewIndex[_]): IndexLabel =
    IndexLabel.unsafe(view.index)

}

object GraphAnalyticsIndexingStream {

  type EventStream = Stream[Task, Envelope[Event]]

  sealed trait GraphElement extends Product with Serializable

  object GraphElement {
    final case class NewFileElement(id: Iri) extends GraphElement

    final case class ResourceElement(id: Iri, rev: Long) extends GraphElement
  }

  def apply(
      client: ElasticSearchClient,
      projects: Projects,
      eventLog: EventLog[Envelope[Event]],
      resourceParser: ResourceParser,
      cache: ProgressesCache,
      config: ExternalIndexingConfig,
      projection: Projection[Unit]
  )(implicit sc: Scheduler) =
    new GraphAnalyticsIndexingStream(
      client,
      (project: ProjectRef, offset: Offset) =>
        eventLog
          .projectEvents(projects, project, offset)
          .hideErrorsWith(r => new IllegalStateException(r.reason)),
      resourceParser,
      cache,
      config,
      projection
    )

}
