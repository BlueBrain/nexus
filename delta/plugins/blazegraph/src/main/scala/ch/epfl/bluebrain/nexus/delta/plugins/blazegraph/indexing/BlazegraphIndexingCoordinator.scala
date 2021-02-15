package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.indexing

import akka.actor.typed.ActorSystem
import akka.persistence.query.NoOffset
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategy
import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategy.logError
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.BlazegraphViews
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.{BlazegraphClient, SparqlWriteQuery}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphView.IndexingBlazegraphView
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphViewsConfig
import ch.epfl.bluebrain.nexus.delta.rdf.RdfError.InvalidIri
import ch.epfl.bluebrain.nexus.delta.rdf.Triple.Triple
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{nxv, rdf}
import ch.epfl.bluebrain.nexus.delta.rdf.graph.Graph
import ch.epfl.bluebrain.nexus.delta.rdf.utils.UriUtils
import ch.epfl.bluebrain.nexus.delta.sdk.eventlog.GlobalEventLog
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.indexing.{IndexingStreamCoordinator, ViewLens}
import ch.epfl.bluebrain.nexus.delta.sdk.model.IdSegment.IriSegment
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, ResourceF, ResourceUris}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.sourcing.projections.ProjectionStream.{ChunkStreamOps, SimpleStreamOps}
import ch.epfl.bluebrain.nexus.sourcing.projections.stream.StreamSupervisor
import ch.epfl.bluebrain.nexus.sourcing.projections.{Message, Projection, ProjectionId, ProjectionProgress}
import com.typesafe.scalalogging.Logger
import fs2.Stream
import monix.bio.{IO, Task}
import monix.execution.Scheduler

import java.util.Properties
import scala.jdk.CollectionConverters._

object BlazegraphIndexingCoordinator {

  private val logger: Logger = Logger[BlazegraphIndexingCoordinator.type]

  private val indexProperties: Map[String, String] = {
    val props = new Properties()
    props.load(getClass.getResourceAsStream("/blazegraph/index.properties"))
    props.asScala.toMap
  }

  private def illegalArgument[A](error: A) = new IllegalArgumentException(error.toString())

  /**
    * Create a coordinator for indexing Blazegraph view.
    */
  def apply(
      views: BlazegraphViews,
      eventLog: GlobalEventLog[Message[ResourceF[Graph]]],
      client: BlazegraphClient,
      projection: Projection[Unit],
      config: BlazegraphViewsConfig
  )(implicit
      as: ActorSystem[Nothing],
      scheduler: Scheduler,
      base: BaseUri,
      lens: ViewLens[ResourceF[IndexingBlazegraphView]]
  ): Task[IndexingStreamCoordinator[ResourceF[IndexingBlazegraphView]]] = {

    def buildStream(
        viewRes: ResourceF[IndexingBlazegraphView],
        initialProgress: ProjectionProgress[Unit]
    ): Task[Stream[Task, Unit]] = {
      val view                                = viewRes.value
      implicit val projectionId: ProjectionId = viewRes.projectionId
      val namespace                           = s"${config.indexing.prefix}_${view.uuid}_${viewRes.rev}"
      for {
        _     <- client
                   .createNamespace(namespace, indexProperties)
                   .hideErrorsWith(illegalArgument)
        eLog  <- eventLog
                   .stream(view.project, initialProgress.offset, view.resourceTag)
                   .hideErrorsWith(illegalArgument)
        stream = eLog
                   .filterMessage(res =>
                     (view.resourceTypes.isEmpty || res.types.exists(view.resourceTypes.contains))
                       && (view.resourceSchemas.isEmpty || view.resourceSchemas.contains(res.schema.iri))
                       && (!res.deprecated || view.includeDeprecated)
                   )
                   .flatMapMessage { msg =>
                     val graph = if (view.includeMetadata) {
                       val types: Set[Triple] = msg.value.types.map((msg.value.id, rdf.tpe, _))
                       msg.value.value
                         .add(types)
                         .add(nxv.rev.iri, msg.value.rev)
                         .add(nxv.deprecated.iri, msg.value.deprecated)
                         .add(nxv.createdAt.iri, msg.value.createdAt)
                         .add(nxv.updatedAt.iri, msg.value.updatedAt)
                         .add(nxv.updatedBy.iri, msg.value.updatedBy.id)
                         .add(nxv.createdBy.iri, msg.value.createdBy.id)
                         .add(nxv.schemaId.iri, msg.value.schema.iri)
                         .add(nxv.project.iri, ResourceUris.project(view.project).accessUri)
                         .add(nxv.incoming.iri, UriUtils./(msg.value.uris.accessUri, "incoming"))
                         .add(nxv.outgoing.iri, UriUtils./(msg.value.uris.accessUri, "outgoing"))
                     } else
                       msg.value.value
                     graph.toNTriples
                       .flatMap { nTriples =>
                         (msg.value.id / "graph").toUri
                           .leftMap(_ => InvalidIri)
                           .map(
                             SparqlWriteQuery
                               .replace(_, nTriples)
                           )
                       }
                       .fold(msg.failed(_), msg.as)
                   }
                   .runAsyncUnit { bulk =>
                     IO.when(bulk.nonEmpty)(
                       client
                         .bulk(namespace, bulk)
                         .hideErrorsWith(illegalArgument)
                     )
                   }
                   .flatMap(Stream.chunk)
                   .map(_.void)
                   .persistProgress(initialProgress, projection, config.persist)
      } yield stream
    }

    val indexingRetryStrategy =
      RetryStrategy[Throwable](config.indexing.retry, _ => true, logError(logger, "blazegraph indexing"))

    for {
      coordinator <-
        IndexingStreamCoordinator[ResourceF[IndexingBlazegraphView]](
          "BlazegraphViewsCoordinator",
          buildStream,
          projection,
          config.processor,
          indexingRetryStrategy
        )
      _           <- startIndexing(views, coordinator, config)
    } yield coordinator
  }

  private def startIndexing(
      views: BlazegraphViews,
      coordinator: IndexingStreamCoordinator[ResourceF[IndexingBlazegraphView]],
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
