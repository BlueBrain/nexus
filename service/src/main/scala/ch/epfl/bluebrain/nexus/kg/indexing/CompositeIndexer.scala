package ch.epfl.bluebrain.nexus.kg.indexing

import akka.NotUsed
import akka.actor.{ActorRef, ActorSystem, Props}
import akka.persistence.query.scaladsl.EventsByTagQuery
import akka.persistence.query.{EventEnvelope, Offset, PersistenceQuery}
import akka.stream.SourceShape
import akka.stream.scaladsl._
import akka.util.Timeout
import cats.effect.{Effect, Timer}
import cats.implicits._
import ch.epfl.bluebrain.nexus.admin.projects.ProjectResource
import ch.epfl.bluebrain.nexus.commons.sparql.client.SparqlWriteQuery
import ch.epfl.bluebrain.nexus.admin.index.ProjectCache
import ch.epfl.bluebrain.nexus.kg.client.{KgClient, KgClientConfig}
import ch.epfl.bluebrain.nexus.kg.indexing.View.CompositeView.Source.RemoteProjectEventStream
import ch.epfl.bluebrain.nexus.kg.indexing.View.{CompositeView, Filter, SparqlView}
import ch.epfl.bluebrain.nexus.kg.indexing.View.CompositeView.{Source => CompositeSource}
import ch.epfl.bluebrain.nexus.kg.resources._
import ch.epfl.bluebrain.nexus.kg.routes.Clients
import ch.epfl.bluebrain.nexus.service.config.AppConfig
import ch.epfl.bluebrain.nexus.sourcing.projections.ProgressFlow.{Eval, PairMsg, ProgressFlowElem, ProgressFlowList}
import ch.epfl.bluebrain.nexus.sourcing.projections.ProjectionProgress.{NoProgress, OffsetsProgress}
import ch.epfl.bluebrain.nexus.sourcing.projections._
import com.typesafe.scalalogging.Logger

import scala.concurrent.ExecutionContext

// $COVERAGE-OFF$
@SuppressWarnings(Array("MaxParameters"))
object CompositeIndexer {

  implicit private val log: Logger = Logger[CompositeIndexer.type]

  /**
    * Starts the index process for a CompositeIndexer with the ability to reset the offset to NoOffset on the passed projections
    *
    * @param view            the view for which to start the index
    * @param resources       the resources operations
    * @param project         the project to which the resource belongs
    * @param restartProgress the seq of progressId to be restarted
    */
  final def start[F[_]: Timer: Clients: Effect](
      view: CompositeView,
      resources: Resources[F],
      project: ProjectResource,
      restartProgress: Set[String]
  )(implicit
      as: ActorSystem,
      actorInitializer: (Props, String) => ActorRef,
      config: AppConfig,
      projections: Projections[F, String],
      projectCache: ProjectCache[F]
  ): StreamSupervisor[F, ProjectionProgress] = {

    val mainProgressId: String = view.progressId

    val initialProgressF = projections
      .progress(mainProgressId)
      .map {
        case progress: OffsetsProgress =>
          restartProgress.foldLeft(progress)((acc, progressId) => acc.replace(progressId -> NoProgress))
        case other                     => other // Do nothing, a composite view cannot be a single progress
      }
      .flatMap(newProgress => projections.recordProgress(mainProgressId, newProgress) >> newProgress.pure[F])

    start(view, resources, project, initialProgressF)
  }

  /**
    * Starts the index process for a CompositeIndexer with the ability to restart the offset on the whole view
    *
    * @param view          the view for which to start the index
    * @param resources     the resources operations
    * @param project       the project to which the resource belongs
    * @param restartOffset a flag to decide whether to restart from the beginning or to resume from the previous offset
    */
  final def start[F[_]: Timer: Clients](
      view: CompositeView,
      resources: Resources[F],
      project: ProjectResource,
      restartOffset: Boolean
  )(implicit
      as: ActorSystem,
      actorInitializer: (Props, String) => ActorRef,
      config: AppConfig,
      projections: Projections[F, String],
      projectCache: ProjectCache[F],
      F: Effect[F]
  ): StreamSupervisor[F, ProjectionProgress] = {

    val mainProgressId: String = view.progressId

    val initialProgressF: F[ProjectionProgress] =
      if (restartOffset) projections.recordProgress(mainProgressId, NoProgress) >> F.pure(NoProgress)
      else projections.progress(mainProgressId)

    start(view, resources, project, initialProgressF)
  }

  private def start[F[_]: Timer](
      view: CompositeView,
      resources: Resources[F],
      project: ProjectResource,
      initialProgressF: F[ProjectionProgress]
  )(implicit
      clients: Clients[F],
      as: ActorSystem,
      actorInitializer: (Props, String) => ActorRef,
      config: AppConfig,
      projections: Projections[F, String],
      projectCache: ProjectCache[F],
      F: Effect[F]
  ): StreamSupervisor[F, ProjectionProgress] = {
    val mainProgressId: String                 = view.progressId
    val FSome: F[Option[Unit]]                 = F.pure(Option(()))
    implicit val ec: ExecutionContext          = as.dispatcher
    implicit val indexing: IndexingConfig      = config.sparql.indexing
    implicit val metadataOpts: MetadataOptions = MetadataOptions(linksAsIri = true, expandedLinks = true)
    implicit val tm: Timeout                   = Timeout(config.sparql.askTimeout)

    def buildInsertOrDeleteQuery(res: ResourceV, view: SparqlView): SparqlWriteQuery =
      if (res.deprecated && !view.filter.includeDeprecated) view.buildDeleteQuery(res)
      else view.buildInsertQuery(res)

    def fetchRemoteResource(
        event: Event
    )(source: RemoteProjectEventStream, filter: Filter)(implicit project: ProjectResource): F[Option[ResourceV]] = {
      val clientCfg = KgClientConfig(source.endpoint)
      val client    = KgClient(clientCfg)
      filter.resourceTag.filter(_.trim.nonEmpty) match {
        case Some(tag) => client.resource(project, event.id.value, tag)(source.token)
        case _         => client.resource(project, event.id.value)(source.token)
      }
    }

    def fetchRemoteEvents(source: RemoteProjectEventStream, offset: Offset): Source[EventEnvelope, NotUsed] = {
      val clientCfg = KgClientConfig(source.endpoint)
      val client    = KgClient(clientCfg)
      client.events(source.project, offset)(source.token)
    }

    def sourceGraph(source: CompositeSource, initial: ProjectionProgress)(implicit
        proj: ProjectResource
    ): Source[PairMsg[Unit], _] = {
      Source.fromGraph(GraphDSL.create() { implicit b =>
        // format: off
        import GraphDSL.Implicits._
        val sourceView        = view.sparqlView(source)
        val sourceProgressId  = source.id.asString
        val sparqlClient      = clients.sparql.copy(namespace = sourceView.index).withRetryPolicy(config.sparql.indexing.retry)
        val sparqlClientQuery = sparqlClient.withRetryPolicy(config.sparql.query)
        val sourceMinProgress = initial.minProgressFilter(pId => pId == sourceProgressId || pId.startsWith(source.id.asString)).offset

        val streamSource: Source[PairMsg[Any], _] = source match {
          case s: RemoteProjectEventStream => fetchRemoteEvents(s, sourceMinProgress).map[PairMsg[Any]](e => Right(Message(e, sourceProgressId)))
          case _ =>
            PersistenceQuery(as)
              .readJournalFor[EventsByTagQuery](config.persistence.queryJournalPlugin)
              .eventsByTag(s"project=${proj.uuid}", sourceMinProgress)
              .map[PairMsg[Any]](e => Right(Message(e, sourceProgressId)))
        }
        val mainFlow = ProgressFlowElem[F, Any]
          .collectCast[Event]
          .groupedWithin(indexing.batch, indexing.batchTimeout)
          .distinct()
          .mapAsync { event =>
            source match {
              case s: RemoteProjectEventStream  => fetchRemoteResource(event)(s, sourceView.filter)
              case _                            => sourceView.toResource(resources, event)
            }
          }
          .collectSome[ResourceV]
          .collect {
            case res if sourceView.allowedSchemas(res) && sourceView.allowedTypes(res) => res -> buildInsertOrDeleteQuery(res, sourceView)
            case res if sourceView.allowedSchemas(res) =>                                 res -> sourceView.buildDeleteQuery(res)
          }
          .runAsyncBatch(list => sparqlClient.bulk(list.map { case (_, bulkQuery) => bulkQuery }))(Eval.After(initial.progress(sourceProgressId).offset))
          .map { case (res, _) => res }
          .mergeEmit()

        val projectionsFlow = view.projections.map { projection =>
          val projView = projection.view
          val projProgressId = view.progressId(source.id, projView.id)
          ProgressFlowElem[F, ResourceV]
            .select(projProgressId)
            .evaluateAfter(initial.progress(projProgressId).offset)
            .collect {
              case res if projView.allowedSchemas(res) && projView.allowedTypes(res) && projView.allowedTag(res) => res
            }
            .mapAsync(res => projection.runQuery(res)(sparqlClientQuery).map(res -> _.asGraph))
            .mapAsync {
              case (res, None)                                 => projView.deleteResource[F](res.id) >> FSome
              case (res, Some(graph)) if graph.triples.isEmpty => projView.deleteResource[F](res.id) >> FSome
              case (res, Some(graph))                          => projection.indexResourceGraph[F](res, graph)
            }
            .collectSome[Unit]
        }

        val broadcast = b.add(Broadcast[PairMsg[ResourceV]](view.projections.size))
        val merge     = b.add(ZipWithN[PairMsg[Unit], List[PairMsg[Unit]]](_.toList)(view.projections.size))

        val combine = b.add(ProgressFlowList[F, Unit].mergeCombine().flow)

        streamSource ~> mainFlow.flow ~> broadcast
                                         projectionsFlow.foreach(broadcast ~> _.flow ~> merge)
                                                                                        merge ~> combine.in

        SourceShape(combine.out)
        // format: on
      })
    }

    val init = view.defaultSparqlView.createIndex >> view.projections.toList.traverse(_.view.createIndex) >> F.unit

    val sourceResolvedProjectsF = (init >> initialProgressF).flatMap { initial =>
      val sourcesF: F[Vector[Option[(CompositeSource, ProjectResource)]]] = view.sources.toVector.traverse {
        case s: CompositeSource.ProjectEventStream       => F.pure(Some(s -> project))
        case s: CompositeSource.CrossProjectEventStream  => projectCache.getBy(s.project).map(_.map(s -> _))
        case s: CompositeSource.RemoteProjectEventStream => s.fetchProject[F].map(_.map(s -> _))
      }
      sourcesF.map(list => (initial, list.collect { case Some((source, project)) => source -> project }))
    }

    val sourceF: F[Source[ProjectionProgress, _]] = sourceResolvedProjectsF.map {
      case (initial, sourcesProject) =>
        val sources = sourcesProject.map { case (s, project) => sourceGraph(s, initial)(project) }

        Source
          .fromGraph(GraphDSL.create() { implicit b =>
            import GraphDSL.Implicits._
            val persistFlow = b.add(ProgressFlowElem[F, Unit].toPersistedProgress(mainProgressId, initial))

            val merge = b.add(Merge[PairMsg[Unit]](sourcesProject.size))
            // format: off
            sources.foreach(_ ~> merge)
                                 merge ~> persistFlow.in
            // format: on
            SourceShape(persistFlow.out)
          })
          .via(kamonViewMetricsFlow(view, project))
    }
    StreamSupervisor.start(sourceF, mainProgressId, actorInitializer)
  }
}
// $COVERAGE-ON$
