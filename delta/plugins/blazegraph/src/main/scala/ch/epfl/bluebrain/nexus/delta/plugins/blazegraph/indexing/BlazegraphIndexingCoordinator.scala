package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.indexing

import akka.actor.typed.ActorSystem
import akka.http.scaladsl.model.Uri
import cats.syntax.functor._
import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategy
import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategy.logError
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClasspathResourceUtils
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.{BlazegraphClient, SparqlWriteQuery}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.indexing.BlazegraphIndexingCoordinator.illegalArgument
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphView.IndexingBlazegraphView
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.{BlazegraphViewsConfig, IndexingViewResource}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.RdfError.InvalidIri
import ch.epfl.bluebrain.nexus.delta.rdf.graph.{Graph, NTriples}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.ProgressesStatistics.ProgressesCache
import ch.epfl.bluebrain.nexus.delta.sdk.eventlog.GlobalEventLog
import ch.epfl.bluebrain.nexus.delta.sdk.indexing.IndexingStreamCoordinator
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, ResourceF}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sourcing.config.ExternalIndexingConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.ProjectionStream.{ChunkStreamOps, SimpleStreamOps}
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.{Message, Projection, ProjectionId, ProjectionProgress}
import com.typesafe.scalalogging.Logger
import fs2.Stream
import monix.bio.{IO, Task}
import monix.execution.Scheduler

private class IndexingStream(
    client: BlazegraphClient,
    cache: ProgressesCache,
    viewRes: IndexingViewResource,
    config: BlazegraphViewsConfig
)(implicit cr: RemoteContextResolution, baseUri: BaseUri) {
  implicit val indexCfg: ExternalIndexingConfig   = config.indexing
  private val view: IndexingBlazegraphView        = viewRes.value
  private val namespace: String                   = viewRes.index
  implicit private val projectionId: ProjectionId = viewRes.projectionId
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
      eventLog: GlobalEventLog[Message[ResourceF[Graph]]],
      projection: Projection[Unit],
      initialProgress: ProjectionProgress[Unit]
  )(implicit sc: Scheduler): Task[Stream[Task, Unit]] =
    for {
      props <- ClasspathResourceUtils.ioPropertiesOf("blazegraph/index.properties")
      _     <- client.createNamespace(namespace, props)
      _     <- cache.remove(projectionId)
      _     <- cache.put(projectionId, initialProgress)
      eLog  <- eventLog.stream(view.project, initialProgress.offset, view.resourceTag).mapError(illegalArgument)
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

  type StartCoordinator              = IndexingViewResource => Task[Unit]
  type StopCoordinator               = IndexingViewResource => Task[Unit]
  type BlazegraphIndexingCoordinator = IndexingStreamCoordinator[IndexingViewResource]

  private val logger: Logger = Logger[BlazegraphIndexingCoordinator.type]

  private[indexing] def illegalArgument[A](error: A) =
    new IllegalArgumentException(error.toString)

  /**
    * Create a coordinator for indexing triples into Blazegraph namespaces triggered and customized by the BlazegraphViews.
    */
  def apply(
      eventLog: GlobalEventLog[Message[ResourceF[Graph]]],
      client: BlazegraphClient,
      projection: Projection[Unit],
      cache: ProgressesCache,
      config: BlazegraphViewsConfig
  )(implicit
      as: ActorSystem[Nothing],
      scheduler: Scheduler,
      base: BaseUri,
      resolution: RemoteContextResolution
  ): Task[BlazegraphIndexingCoordinator] = {

    val indexingRetryStrategy =
      RetryStrategy[Throwable](config.indexing.retry, _ => true, logError(logger, "blazegraph indexing"))

    implicit val indexCfg: ExternalIndexingConfig = config.indexing

    IndexingStreamCoordinator[ResourceF[IndexingBlazegraphView]](
      "BlazegraphViewsCoordinator",
      (res, progress) => new IndexingStream(client, cache, res, config).build(eventLog, projection, progress),
      client.deleteNamespace(_).void,
      projection,
      config.aggregate.processor,
      indexingRetryStrategy
    )
  }
}
