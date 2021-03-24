package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.indexing

import akka.actor.typed.ActorSystem
import akka.http.scaladsl.model.Uri
import cats.syntax.functor._
import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategy
import ch.epfl.bluebrain.nexus.delta.kernel.utils.{ClasspathResourceUtils, UUIDF}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.BlazegraphViews
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.{BlazegraphClient, SparqlWriteQuery}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphView.IndexingBlazegraphView
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphViewRejection.DifferentBlazegraphViewType
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphViewsConfig
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.RdfError.InvalidIri
import ch.epfl.bluebrain.nexus.delta.rdf.graph.{Graph, NTriples}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.MigrationState
import ch.epfl.bluebrain.nexus.delta.sdk.ProgressesStatistics.ProgressesCache
import ch.epfl.bluebrain.nexus.delta.sdk.views.indexing.IndexingStreamCoordinator
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.views.model.ViewIndex
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, ResourceF}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sourcing.config.ExternalIndexingConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.ProjectionStream.{ChunkStreamOps, SimpleStreamOps}
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.{Projection, ProjectionId, ProjectionProgress}
import com.typesafe.scalalogging.Logger
import fs2.Stream
import monix.bio.{IO, Task, UIO}
import monix.execution.Scheduler

private class IndexingStream(
    client: BlazegraphClient,
    cache: ProgressesCache,
    viewIndex: ViewIndex[IndexingBlazegraphView],
    config: BlazegraphViewsConfig
)(implicit cr: RemoteContextResolution, baseUri: BaseUri) {
  implicit val indexCfg: ExternalIndexingConfig   = config.indexing
  private val view: IndexingBlazegraphView        = viewIndex.underlyingView
  private val namespace: String                   = viewIndex.index
  implicit private val projectionId: ProjectionId = viewIndex.projectionId
  implicit private val cl: ClassLoader            = getClass.getClassLoader

  private def deleteOrIndex(res: ResourceF[Graph]): Task[SparqlWriteQuery] =
    if (res.deprecated && !view.includeDeprecated) delete(res)
    else index(res)

  private def delete(res: ResourceF[Graph]): Task[SparqlWriteQuery] =
    namedGraph(res.id).map(SparqlWriteQuery.drop)

  private def index(res: ResourceF[Graph]): Task[SparqlWriteQuery] =
    for {
      triples    <- toTriples(res)
      namedGraph <- namedGraph(res.id)
    } yield SparqlWriteQuery.replace(namedGraph, triples)

  private def toTriples(res: ResourceF[Graph]): Task[NTriples] =
    for {
      metadataGraph <- if (view.includeMetadata) res.void.toGraph else Task.delay(Graph.empty)
      fullGraph      = res.value ++ metadataGraph
      nTriples      <- IO.fromEither(fullGraph.toNTriples)
    } yield nTriples

  private def namedGraph[A](id: Iri): Task[Uri] =
    IO.fromEither((id / "graph").toUri).mapError(_ => InvalidIri)

  private def containsSchema[A](res: ResourceF[A]): Boolean =
    view.resourceSchemas.isEmpty || view.resourceSchemas.contains(res.schema.iri)

  private def containsTypes[A](res: ResourceF[A]): Boolean =
    view.resourceTypes.isEmpty || view.resourceTypes.intersect(res.types).nonEmpty

  def build(
      eventLog: BlazegraphIndexingEventLog,
      projection: Projection[Unit],
      initialProgress: ProjectionProgress[Unit]
  )(implicit sc: Scheduler): Task[Stream[Task, Unit]] =
    for {
      props <- ClasspathResourceUtils.ioPropertiesOf("blazegraph/index.properties")
      _     <- client.createNamespace(namespace, props)
      _     <- cache.remove(projectionId)
      _     <- cache.put(projectionId, initialProgress)
      eLog   = eventLog.stream(view.project, initialProgress.offset, view.resourceTag)
      stream = eLog
                 .evalMapFilterValue {
                   case res if containsSchema(res) && containsTypes(res) => deleteOrIndex(res).map(Some.apply)
                   case res if containsSchema(res)                       => delete(res).map(Some.apply)
                   case _                                                => Task.pure(None)
                 }
                 .runAsyncUnit { bulk =>
                   IO.when(bulk.nonEmpty)(client.bulk(namespace, bulk))
                 }
                 .flatMap(Stream.chunk)
                 .map(_.void)
                 .persistProgressWithCache(
                   initialProgress,
                   projection,
                   cache.put,
                   config.indexing.projection,
                   config.indexing.cache
                 )
    } yield stream

}

object BlazegraphIndexingCoordinator {

  implicit private val logger: Logger = Logger[BlazegraphIndexingCoordinator.type]

  type BlazegraphIndexingCoordinator = IndexingStreamCoordinator[IndexingBlazegraphView]

  private def fetchView(views: BlazegraphViews, config: BlazegraphViewsConfig) = (id: Iri, project: ProjectRef) =>
    views
      .fetchIndexingView(id, project)
      .flatMap { res =>
        UIO.pure(
          Some(
            ViewIndex(
              res.value.project,
              res.id,
              BlazegraphViews.projectionId(res),
              BlazegraphViews.index(res, config.indexing),
              res.rev,
              res.deprecated,
              res.value
            )
          )
        )
      }
      .onErrorHandle {
        case _: DifferentBlazegraphViewType =>
          logger.debug(s"Filtering out aggregate views")
          None
        case r                              =>
          logger.error(
            s"While attempting to start indexing view $id in project $project, the rejection $r was encountered"
          )
          None
      }

  /**
    * Create a coordinator for indexing triples into Blazegraph namespaces triggered and customized by the BlazegraphViews.
    */
  def apply(
      views: BlazegraphViews,
      indexingLog: BlazegraphIndexingEventLog,
      client: BlazegraphClient,
      projection: Projection[Unit],
      cache: ProgressesCache,
      config: BlazegraphViewsConfig
  )(implicit
      uuidF: UUIDF,
      as: ActorSystem[Nothing],
      scheduler: Scheduler,
      base: BaseUri,
      resolution: RemoteContextResolution
  ): Task[BlazegraphIndexingCoordinator] = Task
    .delay {
      val indexingRetryStrategy =
        RetryStrategy.retryOnNonFatal(config.indexing.retry, logger, "blazegraph indexing")

      new IndexingStreamCoordinator[IndexingBlazegraphView](
        BlazegraphViews.moduleType,
        fetchView(views, config),
        (res, progress) => new IndexingStream(client, cache, res, config).build(indexingLog, projection, progress),
        client.deleteNamespace(_).logAndDiscardErrors("deleting blazegraph namespace").void,
        projection,
        indexingRetryStrategy
      )
    }
    .tapEval { coordinator =>
      IO.unless(MigrationState.isIndexingDisabled)(
        BlazegraphViewsIndexing.startIndexingStreams(config.indexing, views, coordinator)
      )
    }
}
