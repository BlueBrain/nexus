package ch.epfl.bluebrain.nexus.kg.indexing

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.persistence.query.PersistenceQuery
import akka.persistence.query.scaladsl.EventsByTagQuery
import akka.stream.scaladsl.Source
import akka.util.Timeout
import cats.effect.{Effect, Timer}
import cats.implicits._
import ch.epfl.bluebrain.nexus.admin.projects.ProjectResource
import ch.epfl.bluebrain.nexus.commons.sparql.client.{BlazegraphClient, SparqlWriteQuery}
import ch.epfl.bluebrain.nexus.kg.indexing.View.SparqlView
import ch.epfl.bluebrain.nexus.kg.resources._
import ch.epfl.bluebrain.nexus.kg.routes.Clients
import ch.epfl.bluebrain.nexus.delta.config.AppConfig
import ch.epfl.bluebrain.nexus.sourcing.projections.ProgressFlow.{PairMsg, ProgressFlowElem}
import ch.epfl.bluebrain.nexus.sourcing.projections.ProjectionProgress.NoProgress
import ch.epfl.bluebrain.nexus.sourcing.projections._

import scala.concurrent.ExecutionContext

// $COVERAGE-OFF$
@SuppressWarnings(Array("MaxParameters"))
object SparqlIndexer {

  /**
    * Starts the index process for an Sparql client
    *
    * @param view          the view for which to start the index
    * @param resources     the resources operations
    * @param project       the project to which the resource belongs
    * @param restartOffset a flag to decide whether to restart from the beginning or to resume from the previous offset
    */
  final def start[F[_]: Timer](
      view: SparqlView,
      resources: Resources[F],
      project: ProjectResource,
      restartOffset: Boolean
  )(implicit
      as: ActorSystem,
      actorInitializer: (Props, String) => ActorRef,
      projections: Projections[F, String],
      F: Effect[F],
      clients: Clients[F],
      config: AppConfig
  ): StreamSupervisor[F, ProjectionProgress] = {

    implicit val ec: ExecutionContext          = as.dispatcher
    implicit val p: ProjectResource            = project
    implicit val indexing: IndexingConfig      = config.sparql.indexing
    implicit val metadataOpts: MetadataOptions = MetadataOptions(linksAsIri = true, expandedLinks = true)
    implicit val tm: Timeout                   = Timeout(config.sparql.askTimeout)

    val client: BlazegraphClient[F] =
      clients.sparql.copy(namespace = view.index).withRetryPolicy(config.sparql.indexing.retry)

    def buildInsertOrDeleteQuery(res: ResourceV): SparqlWriteQuery =
      if (res.deprecated && !view.filter.includeDeprecated) view.buildDeleteQuery(res)
      else view.buildInsertQuery(res)

    val initFetchProgressF: F[ProjectionProgress] =
      if (restartOffset)
        projections.recordProgress(view.progressId, NoProgress) >> view.createIndex >> F.pure(NoProgress)
      else view.createIndex >> projections.progress(view.progressId)

    val sourceF: F[Source[ProjectionProgress, _]] = initFetchProgressF.map { initial =>
      val flow = ProgressFlowElem[F, Any]
        .collectCast[Event]
        .groupedWithin(indexing.batch, indexing.batchTimeout)
        .distinct()
        .mapAsync(view.toResource(resources, _))
        .collectSome[ResourceV]
        .collect {
          case res if view.allowedSchemas(res) && view.allowedTypes(res) => buildInsertOrDeleteQuery(res)
          case res if view.allowedSchemas(res)                           => view.buildDeleteQuery(res)
        }
        .runAsyncBatch(client.bulk(_))()
        .mergeEmit()
        .toPersistedProgress(view.progressId, initial)

      PersistenceQuery(as)
        .readJournalFor[EventsByTagQuery](config.persistence.queryJournalPlugin)
        .eventsByTag(s"project=${view.ref.id}", initial.minProgress.offset)
        .map[PairMsg[Any]](e => Right(Message(e, view.progressId)))
        .via(flow)
        .via(kamonViewMetricsFlow(view, project))
    }
    StreamSupervisor.start(sourceF, view.progressId, actorInitializer)
  }
}
// $COVERAGE-ON$
