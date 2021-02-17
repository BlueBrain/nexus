package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.indexing

import akka.actor.typed.ActorSystem
import akka.http.scaladsl.model.Uri
import akka.persistence.query.NoOffset
import cats.syntax.functor._
import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategy
import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategy.logError
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.BlazegraphViews
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.{BlazegraphClient, SparqlWriteQuery}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.indexing.BlazegraphIndexingCoordinator.{illegalArgument, ProgressCache}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphView.IndexingBlazegraphView
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.{BlazegraphViewsConfig, IndexingViewResource}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.RdfError.InvalidIri
import ch.epfl.bluebrain.nexus.delta.rdf.graph.{Graph, NTriples}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.cache.KeyValueStore
import ch.epfl.bluebrain.nexus.delta.sdk.eventlog.GlobalEventLog
import ch.epfl.bluebrain.nexus.delta.sdk.indexing.IndexingStreamCoordinator
import ch.epfl.bluebrain.nexus.delta.sdk.model.IdSegment.IriSegment
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, ResourceF}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.sourcing.config.ExternalIndexingConfig
import ch.epfl.bluebrain.nexus.sourcing.projections.ProjectionStream.{ChunkStreamOps, SimpleStreamOps}
import ch.epfl.bluebrain.nexus.sourcing.projections.stream.StreamSupervisor
import ch.epfl.bluebrain.nexus.sourcing.projections.{Message, Projection, ProjectionId, ProjectionProgress}
import com.typesafe.scalalogging.Logger
import fs2.Stream
import monix.bio.{IO, Task}
import monix.execution.Scheduler

import java.util.Properties
import scala.jdk.CollectionConverters._

private class IndexingStream(
    client: BlazegraphClient,
    cache: ProgressCache,
    viewRes: IndexingViewResource,
    config: BlazegraphViewsConfig
)(implicit cr: RemoteContextResolution, baseUri: BaseUri) {
  implicit val indexCfg: ExternalIndexingConfig   = config.indexing
  private val view: IndexingBlazegraphView        = viewRes.value
  private val namespace: String                   = viewRes.index
  implicit private val projectionId: ProjectionId = viewRes.projectionId

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

  private val indexProperties: Map[String, String] = {
    val props = new Properties()
    props.load(getClass.getResourceAsStream("/blazegraph/index.properties"))
    props.asScala.toMap
  }

  def build(
      eventLog: GlobalEventLog[Message[ResourceF[Graph]]],
      projection: Projection[Unit],
      initialProgress: ProjectionProgress[Unit]
  )(implicit sc: Scheduler): IO[Nothing, Stream[Task, Unit]] =
    for {
      _     <- client.createNamespace(namespace, indexProperties).hideErrorsWith(illegalArgument)
      _     <- cache.remove(projectionId)
      _     <- cache.put(projectionId, initialProgress)
      eLog  <- eventLog.stream(view.project, initialProgress.offset, view.resourceTag).hideErrorsWith(illegalArgument)
      stream = eLog
                 .evalMapFilterValue {
                   case res if containsSchema(res) && containsTypes(res) => deleteOrIndex(res).map(Some.apply)
                   case res if containsSchema(res)                       => delete(res).map(Some.apply)
                   case _                                                => Task.pure(None)
                 }
                 .runAsyncUnit { bulk =>
                   IO.when(bulk.nonEmpty)(client.bulk(namespace, bulk).hideErrorsWith(illegalArgument))
                 }
                 .flatMap(Stream.chunk)
                 .map(_.void)
                 .persistProgressWithCache(initialProgress, projection, cache.put, config.indexing.persist)
    } yield stream

}

object BlazegraphIndexingCoordinator {

  type ProgressCache = KeyValueStore[ProjectionId, ProjectionProgress[Unit]]

  private val logger: Logger = Logger[BlazegraphIndexingCoordinator.type]

  private[indexing] def illegalArgument[A](error: A) =
    new IllegalArgumentException(error.toString)

  /**
    * Create a coordinator for indexing triples into Blazegraph namespaces triggered and customized by the BlazegraphViews.
    */
  def apply(
      views: BlazegraphViews,
      eventLog: GlobalEventLog[Message[ResourceF[Graph]]],
      client: BlazegraphClient,
      projection: Projection[Unit],
      cache: ProgressCache,
      config: BlazegraphViewsConfig
  )(implicit
      as: ActorSystem[Nothing],
      scheduler: Scheduler,
      base: BaseUri,
      resolution: RemoteContextResolution
  ): Task[IndexingStreamCoordinator[IndexingViewResource]] = {

    val indexingRetryStrategy =
      RetryStrategy[Throwable](config.indexing.retry, _ => true, logError(logger, "blazegraph indexing"))

    implicit val indexCfg: ExternalIndexingConfig = config.indexing

    for {
      coordinator <-
        IndexingStreamCoordinator[IndexingViewResource](
          "BlazegraphViewsCoordinator",
          (res, progress) => new IndexingStream(client, cache, res, config).build(eventLog, projection, progress),
          client.deleteNamespace(_).hideErrorsWith(illegalArgument).as(()),
          projection,
          config.processor,
          indexingRetryStrategy
        )
      _           <- startIndexing(views, coordinator, config)
    } yield coordinator
  }

  private def startIndexing(
      views: BlazegraphViews,
      coordinator: IndexingStreamCoordinator[IndexingViewResource],
      config: BlazegraphViewsConfig
  )(implicit as: ActorSystem[Nothing], sc: Scheduler) =
    StreamSupervisor(
      "BlazegraphViewsIndexer",
      streamTask = Task.delay(
        views
          .events(NoOffset)
          .evalMapFilter { ev =>
            views.fetch(IriSegment(ev.event.id), ev.event.project).map(Some(_)).redeemCause(_ => None, identity)
          }
          .evalMap {
            case res @ ResourceF(_, _, _, _, false, _, _, _, _, _, view: IndexingBlazegraphView) =>
              coordinator.start(res.as(view))
            case res @ ResourceF(_, _, _, _, true, _, _, _, _, _, view: IndexingBlazegraphView)  =>
              coordinator.stop(res.as(view))
            case _                                                                               => Task.unit
          }
      ),
      retryStrategy = RetryStrategy(
        config.indexing.retry,
        _ => true,
        RetryStrategy.logError(logger, "Blazegraph views indexing")
      )
    )
}
