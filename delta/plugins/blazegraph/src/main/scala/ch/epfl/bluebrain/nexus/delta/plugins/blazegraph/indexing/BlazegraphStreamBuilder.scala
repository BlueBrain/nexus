package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.indexing

import akka.http.scaladsl.model.Uri
import cats.syntax.functor._
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClasspathResourceUtils
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.{BlazegraphClient, SparqlWriteQuery}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.indexing.BlazegraphIndexingEventLog.BlazegraphIndexingEventLog
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphView.IndexingBlazegraphView
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphViewsConfig
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.RdfError.InvalidIri
import ch.epfl.bluebrain.nexus.delta.rdf.graph.{Graph, NTriples}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.ProgressesStatistics.ProgressesCache
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, ResourceF}
import ch.epfl.bluebrain.nexus.delta.sdk.views.indexing.StreamBuilder
import ch.epfl.bluebrain.nexus.delta.sdk.views.indexing.StreamBuilder.ProgressStrategy
import ch.epfl.bluebrain.nexus.delta.sdk.views.model.ViewIndex
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.ProjectionProgress.NoProgress
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.ProjectionStream.{ChunkStreamOps, SimpleStreamOps}
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.{Projection, ProjectionId}
import fs2.Stream
import monix.bio.{IO, Task}
import monix.execution.Scheduler

/**
  * Defines how to build a stream for an IndexingBlazegraphView
  */
final class BlazegraphStreamBuilder(
    client: BlazegraphClient,
    cache: ProgressesCache,
    config: BlazegraphViewsConfig,
    eventLog: BlazegraphIndexingEventLog,
    projection: Projection[Unit]
)(implicit cr: RemoteContextResolution, baseUri: BaseUri, sc: Scheduler)
    extends StreamBuilder[IndexingBlazegraphView] {
  override def apply(
      prev: Option[ViewIndex[IndexingBlazegraphView]],
      view: ViewIndex[IndexingBlazegraphView],
      progressStrategy: StreamBuilder.ProgressStrategy
  ): Stream[Task, Unit] = {

    implicit val projectionId: ProjectionId = view.projectionId
    Stream
      .eval {
        for {
          _        <- prev.fold(Task.unit)(prev => client.deleteNamespace(prev.index).void >> cache.remove(prev.projectionId))
          props    <- ClasspathResourceUtils.ioPropertiesOf("blazegraph/index.properties")
          _        <- client.createNamespace(view.index, props)
          progress <- progressStrategy match {
                        case ProgressStrategy.Continue    =>
                          projection.progress(projectionId)
                        case ProgressStrategy.FullRestart =>
                          cache.remove(projectionId) >>
                            cache.put(projectionId, NoProgress) >>
                            projection.recordProgress(projectionId, NoProgress).as(NoProgress)
                      }
        } yield progress
      }
      .flatMap { progress =>
        eventLog
          .stream(view.projectRef, progress.offset, view.value.resourceTag)
          .evalMapFilterValue {
            case res if containsSchema(view.value, res) && containsTypes(view.value, res) =>
              deleteOrIndex(view.value, res).map(Some.apply)
            case res if containsSchema(view.value, res)                                   =>
              delete(res).map(Some.apply)
            case _                                                                        =>
              Task.none
          }
          .runAsyncUnit { bulk =>
            IO.when(bulk.nonEmpty)(client.bulk(view.index, bulk))
          }
          .flatMap(Stream.chunk)
          .map(_.void)
          .persistProgressWithCache(
            progress,
            projection,
            cache.put,
            config.indexing.projection,
            config.indexing.cache
          )
      }
  }

  implicit private val cl: ClassLoader = getClass.getClassLoader

  private def deleteOrIndex(view: IndexingBlazegraphView, res: ResourceF[Graph]): Task[SparqlWriteQuery] =
    if (res.deprecated && !view.includeDeprecated) delete(res)
    else index(view, res)

  private def delete(res: ResourceF[Graph]): Task[SparqlWriteQuery] =
    namedGraph(res.id).map(SparqlWriteQuery.drop)

  private def index(view: IndexingBlazegraphView, res: ResourceF[Graph]): Task[SparqlWriteQuery] =
    for {
      triples    <- toTriples(view, res)
      namedGraph <- namedGraph(res.id)
    } yield SparqlWriteQuery.replace(namedGraph, triples)

  private def toTriples(view: IndexingBlazegraphView, res: ResourceF[Graph]): Task[NTriples] =
    for {
      metadataGraph <- if (view.includeMetadata) res.void.toGraph else Task.delay(Graph.empty)
      fullGraph      = res.value ++ metadataGraph
      nTriples      <- IO.fromEither(fullGraph.toNTriples)
    } yield nTriples

  private def namedGraph[A](id: Iri): Task[Uri] =
    IO.fromEither((id / "graph").toUri).mapError(_ => InvalidIri)

  private def containsSchema[A](view: IndexingBlazegraphView, res: ResourceF[A]): Boolean =
    view.resourceSchemas.isEmpty || view.resourceSchemas.contains(res.schema.iri)

  private def containsTypes[A](view: IndexingBlazegraphView, res: ResourceF[A]): Boolean =
    view.resourceTypes.isEmpty || view.resourceTypes.intersect(res.types).nonEmpty

}
