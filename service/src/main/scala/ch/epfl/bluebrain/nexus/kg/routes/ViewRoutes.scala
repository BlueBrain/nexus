package ch.epfl.bluebrain.nexus.kg.routes

import akka.http.scaladsl.model.StatusCodes.{Created, OK}
import akka.http.scaladsl.model.{StatusCodes, Uri}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.unmarshalling.FromEntityUnmarshaller
import akka.persistence.query.{NoOffset, Offset, Sequence, TimeBasedUUID}
import cats.data.{EitherT, OptionT}
import cats.implicits._
import ch.epfl.bluebrain.nexus.admin.client.types.Project
import ch.epfl.bluebrain.nexus.commons.search.QueryResult.UnscoredQueryResult
import ch.epfl.bluebrain.nexus.commons.search.QueryResults
import ch.epfl.bluebrain.nexus.commons.search.QueryResults.UnscoredQueryResults
import ch.epfl.bluebrain.nexus.commons.sparql.client.SparqlResults
import ch.epfl.bluebrain.nexus.commons.test.Resources.jsonContentOf
import ch.epfl.bluebrain.nexus.iam.client.types._
import ch.epfl.bluebrain.nexus.kg.{IdOffset, IdStats}
import ch.epfl.bluebrain.nexus.kg.KgError.InvalidOutputFormat
import ch.epfl.bluebrain.nexus.kg.async.ProjectViewCoordinator
import ch.epfl.bluebrain.nexus.kg.cache._
import ch.epfl.bluebrain.nexus.kg.config.AppConfig
import ch.epfl.bluebrain.nexus.kg.config.Contexts._
import ch.epfl.bluebrain.nexus.kg.config.Schemas._
import ch.epfl.bluebrain.nexus.kg.config.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.kg.directives.AuthDirectives._
import ch.epfl.bluebrain.nexus.kg.directives.PathDirectives._
import ch.epfl.bluebrain.nexus.kg.directives.ProjectDirectives._
import ch.epfl.bluebrain.nexus.kg.directives.QueryDirectives._
import ch.epfl.bluebrain.nexus.kg.indexing.Statistics.ViewStatistics
import ch.epfl.bluebrain.nexus.kg.indexing.View.CompositeView.Projection.{ElasticSearchProjection, SparqlProjection}
import ch.epfl.bluebrain.nexus.kg.indexing.View._
import ch.epfl.bluebrain.nexus.kg.indexing.{IdentifiedProgress, View}
import ch.epfl.bluebrain.nexus.kg.marshallers.instances._
import ch.epfl.bluebrain.nexus.kg.resources.Rejection.NotFound
import ch.epfl.bluebrain.nexus.kg.resources.Rejection.NotFound.notFound
import ch.epfl.bluebrain.nexus.kg.resources._
import ch.epfl.bluebrain.nexus.kg.resources.syntax._
import ch.epfl.bluebrain.nexus.kg.routes.OutputFormat._
import ch.epfl.bluebrain.nexus.kg.routes.ViewRoutes._
import ch.epfl.bluebrain.nexus.kg.search.QueryResultEncoder._
import ch.epfl.bluebrain.nexus.rdf.Iri.AbsoluteIri
import ch.epfl.bluebrain.nexus.rdf.implicits._
import ch.epfl.bluebrain.nexus.sourcing.projections.syntax._
import io.circe.syntax._
import io.circe.{Encoder, Json}
import kamon.Kamon
import kamon.instrumentation.akka.http.TracingDirectives.operationName
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global

class ViewRoutes private[routes] (
    views: Views[Task],
    tags: Tags[Task],
    coordinator: ProjectViewCoordinator[Task]
)(implicit
    acls: AccessControlLists,
    caller: Caller,
    project: Project,
    projectCache: ProjectCache[Task],
    viewCache: ViewCache[Task],
    clients: Clients[Task],
    config: AppConfig,
    um: FromEntityUnmarshaller[String]
) {

  private val emptyEsList: Json = jsonContentOf("/elasticsearch/empty-list.json")

  import clients._

  /**
    * Routes for views. Those routes should get triggered after the following segments have been consumed:
    * <ul>
    *   <li> {prefix}/views/{org}/{project}. E.g.: v1/views/myorg/myproject </li>
    *   <li> {prefix}/resources/{org}/{project}/{viewSchemaUri}. E.g.: v1/resources/myorg/myproject/view </li>
    * </ul>
    */
  def routes: Route =
    concat(
      // Create view when id is not provided on the Uri (POST)
      (post & noParameter("rev".as[Long]) & pathEndOrSingleSlash) {
        operationName(s"/${config.http.prefix}/views/{org}/{project}") {
          Kamon.currentSpan().tag("resource.operation", "create")
          (hasPermission(write) & projectNotDeprecated) {
            entity(as[Json]) { source =>
              complete(views.create(source).value.runWithStatus(Created))
            }
          }
        }
      },
      // List views
      (get & paginated & searchParams(fixedSchema = viewSchemaUri) & pathEndOrSingleSlash) { (page, params) =>
        operationName(s"/${config.http.prefix}/views/{org}/{project}") {
          extractUri { implicit uri =>
            hasPermission(read).apply {
              val listed = viewCache.getDefaultElasticSearch(project.ref).flatMap(views.list(_, params, page))
              complete(listed.runWithStatus(OK))
            }
          }
        }
      },
      // Consume the view id segment
      pathPrefix(IdSegment) { id =>
        routes(id)
      }
    )

  /**
    * Routes for views when the id is specified.
    * Those routes should get triggered after the following segments have been consumed:
    * <ul>
    *   <li> {prefix}/views/{org}/{project}/{id}. E.g.: v1/views/myorg/myproject/myview </li>
    *   <li> {prefix}/resources/{org}/{project}/{viewSchemaUri}/{id}. E.g.: v1/resources/myorg/myproject/view/myview </li>
    * </ul>
    */
  def routes(id: AbsoluteIri): Route =
    concat(
      // Retrieves view statistics
      (get & pathPrefix("statistics") & pathEndOrSingleSlash) {
        operationName(s"/${config.http.prefix}/views/{org}/{project}/{id}/statistics") {
          hasPermission(read).apply {
            val result = OptionT(coordinator.statistics(id)).toRight(notFound(id.ref))
            complete(result.value.runWithStatus(StatusCodes.OK))
          }
        }
      },
      // Retrieves view offset
      (get & pathPrefix("offset") & pathEndOrSingleSlash) {
        operationName(s"/${config.http.prefix}/views/{org}/{project}/{id}/offset") {
          hasPermission(read).apply {
            val result = OptionT(coordinator.offset(id)).toRight(notFound(id.ref))
            complete(result.value.runWithStatus(StatusCodes.OK))
          }
        }
      },
      // Delete offset from a view. This triggers view restart with initial progress
      (delete & pathPrefix("offset") & pathEndOrSingleSlash) {
        operationName(s"/${config.http.prefix}/views/{org}/{project}/{id}/offset") {
          hasPermission(write).apply {
            val result: OptionT[Task, Offset] = for {
              offset <- OptionT(coordinator.offset(id))
              _      <- OptionT(coordinator.restart(id))
            } yield offset match {
              case CompositeViewOffset(values) => CompositeViewOffset(values.map(_.map(_ => NoOffset)))
              case _                           => NoOffset
            }
            complete(result.toRight(notFound(id.ref)).value.runWithStatus(StatusCodes.OK))
          }
        }
      },
      // Queries a view on the ElasticSearch endpoint
      (post & pathPrefix("_search") & pathEndOrSingleSlash) {
        operationName(s"/${config.http.prefix}/views/{org}/{project}/{id}/_search") {
          (hasPermission(query) & extract(_.request.uri.query())) { params =>
            entity(as[Json]) { query =>
              val result = viewCache.getBy[View](project.ref, id).flatMap(runSearch(params, id, query))
              complete(result.runWithStatus(StatusCodes.OK))
            }
          }
        }
      },
      // Queries a view on the Sparql endpoint
      sparqlRoutes(
        s"/${config.http.prefix}/views/{org}/{project}/{id}/sparql",
        id,
        viewCache
          .getBy[View](project.ref, id)
          .map(_.map {
            case v: CompositeView => v.defaultSparqlView
            case v                => v
          })
      ),
      // Create or update a view (depending on rev query parameter)
      (put & pathEndOrSingleSlash) {
        operationName(s"/${config.http.prefix}/views/{org}/{project}/{id}") {
          (hasPermission(write) & projectNotDeprecated) {
            entity(as[Json]) { source =>
              parameter("rev".as[Long].?) {
                case None      =>
                  Kamon.currentSpan().tag("resource.operation", "create")
                  complete(views.create(Id(project.ref, id), source).value.runWithStatus(Created))
                case Some(rev) =>
                  Kamon.currentSpan().tag("resource.operation", "update")
                  complete(views.update(Id(project.ref, id), rev, source).value.runWithStatus(OK))
              }
            }
          }
        }
      },
      // Deprecate view
      (delete & parameter("rev".as[Long]) & pathEndOrSingleSlash) { rev =>
        operationName(s"/${config.http.prefix}/views/{org}/{project}/{id}") {
          (hasPermission(write) & projectNotDeprecated) {
            complete(views.deprecate(Id(project.ref, id), rev).value.runWithStatus(OK))
          }
        }
      },
      // Fetch view
      (get & pathEndOrSingleSlash) {
        operationName(s"/${config.http.prefix}/views/{org}/{project}/{id}") {
          outputFormat(strict = false, Compacted) {
            case format: NonBinaryOutputFormat =>
              hasPermission(read).apply {
                concat(
                  (parameter("rev".as[Long]) & noParameter("tag")) { rev =>
                    completeWithFormat(views.fetch(Id(project.ref, id), rev).value.runWithStatus(OK))(format)
                  },
                  (parameter("tag") & noParameter("rev")) { tag =>
                    completeWithFormat(views.fetch(Id(project.ref, id), tag).value.runWithStatus(OK))(format)
                  },
                  (noParameter("tag") & noParameter("rev")) {
                    completeWithFormat(views.fetch(Id(project.ref, id)).value.runWithStatus(OK))(format)
                  }
                )
              }
            case other                         => failWith(InvalidOutputFormat(other.toString))
          }
        }
      },
      // Fetch view source
      (get & pathPrefix("source") & pathEndOrSingleSlash) {
        operationName(s"/${config.http.prefix}/views/{org}/{project}/{id}/source") {
          hasPermission(read).apply {
            concat(
              (parameter("rev".as[Long]) & noParameter("tag")) { rev =>
                complete(views.fetchSource(Id(project.ref, id), rev).value.runWithStatus(OK))
              },
              (parameter("tag") & noParameter("rev")) { tag =>
                complete(views.fetchSource(Id(project.ref, id), tag).value.runWithStatus(OK))
              },
              (noParameter("tag") & noParameter("rev")) {
                complete(views.fetchSource(Id(project.ref, id)).value.runWithStatus(OK))
              }
            )
          }
        }
      },
      // Incoming links
      (get & pathPrefix("incoming") & pathEndOrSingleSlash) {
        operationName(s"/${config.http.prefix}/views/{org}/{project}/{id}/incoming") {
          fromPaginated.apply { implicit page =>
            extractUri { implicit uri =>
              hasPermission(read).apply {
                val listed = for {
                  view     <- viewCache.getDefaultSparql(project.ref).toNotFound(nxv.defaultSparqlIndex)
                  _        <- views.fetchSource(Id(project.ref, id))
                  incoming <- EitherT.right[Rejection](views.listIncoming(id, view, page))
                } yield incoming
                complete(listed.value.runWithStatus(OK))
              }
            }
          }
        }
      },
      // Outgoing links
      (get & pathPrefix("outgoing") & parameter("includeExternalLinks".as[Boolean] ? true) & pathEndOrSingleSlash) {
        links =>
          operationName(s"/${config.http.prefix}/views/{org}/{project}/{id}/outgoing") {
            fromPaginated.apply { implicit page =>
              extractUri { implicit uri =>
                hasPermission(read).apply {
                  val listed = for {
                    view     <- viewCache.getDefaultSparql(project.ref).toNotFound(nxv.defaultSparqlIndex)
                    _        <- views.fetchSource(Id(project.ref, id))
                    outgoing <- EitherT.right[Rejection](views.listOutgoing(id, view, page, links))
                  } yield outgoing
                  complete(listed.value.runWithStatus(OK))
                }
              }
            }
          }
      },
      new TagRoutes("views", tags, viewRef, write).routes(id),
      pathPrefix("projections") {
        concat(
          // Consume the projection id segment as underscore if present
          pathPrefix("_")(routesAllProjections(id)),
          // Consume the projection id segment
          pathPrefix(IdSegment)(projectionId => routesProjection(id, projectionId))
        )
      },
      pathPrefix("sources") {
        concat(
          // Consume the source id segment as underscore if present
          pathPrefix("_")(routesAllSources(id)),
          // Consume the source id segment
          pathPrefix(IdSegment)(sourceId => routesSource(id, sourceId))
        )
      }
    )

  private def routesAllProjections(id: AbsoluteIri): Route =
    concat(
      // Queries the ElasticSearch endpoint of every projection on an aggregated search
      (post & pathPrefix("_search") & pathEndOrSingleSlash) {
        operationName(s"/${config.http.prefix}/views/{org}/{project}/{id}/projections/_/_search") {
          (hasPermission(query) & extract(_.request.uri.query())) { params =>
            entity(as[Json]) { query =>
              val result =
                viewCache.getBy[CompositeView](project.ref, id).flatMap(runProjectionsSearch(params, id, query))
              complete(result.runWithStatus(StatusCodes.OK))
            }
          }
        }
      },
      // Queries the Sparql endpoint of every projection on an aggregated search
      sparqlRoutes(
        s"/${config.http.prefix}/views/{org}/{project}/{id}/projections/_/sparql",
        id,
        viewCache.getBy[CompositeView](project.ref, id)
      ),
      // Retrieves statistics from all projections
      (get & pathPrefix("statistics") & pathEndOrSingleSlash) {
        operationName(s"/${config.http.prefix}/views/{org}/{project}/{id}/projections/_/statistics") {
          hasPermission(read).apply {
            val result = coordinator.projectionStats(id).map(toEitherQueryResults(id))
            complete(result.runWithStatus(StatusCodes.OK))
          }
        }
      },
      // Retrieves offset from all projections
      (get & pathPrefix("offset") & pathEndOrSingleSlash) {
        operationName(s"/${config.http.prefix}/views/{org}/{project}/{id}/projections/_/offset") {
          hasPermission(read).apply {
            val result = coordinator.projectionOffsets(id).map(toEitherQueryResults(id))
            complete(result.runWithStatus(StatusCodes.OK))
          }
        }
      },
      // Delete offset from all projections. This triggers view restart with initial progress all projections
      (delete & pathPrefix("offset") & pathEndOrSingleSlash) {
        operationName(s"/${config.http.prefix}/views/{org}/{project}/{id}/projections/_/offset") {
          hasPermission(write).apply {
            val result       = for {
              set <- OptionT(coordinator.projectionOffsets(id))
              _   <- OptionT(coordinator.restartProjections(id))
            } yield toQueryResults(set.map(_.map[Offset](_ => NoOffset)))
            val eitherResult = result.toRight(notFound(id.ref)).value
            complete(eitherResult.runWithStatus(StatusCodes.OK))
          }
        }
      }
    )

  private def routesProjection(id: AbsoluteIri, projectionId: AbsoluteIri): Route =
    concat(
      // Queries a projection view on the ElasticSearch endpoint
      (post & pathPrefix("_search") & pathEndOrSingleSlash) {
        operationName(s"/${config.http.prefix}/views/{org}/{project}/{id}/projections/{projectionId}/_search") {
          (hasPermission(query) & extract(_.request.uri.query())) { params =>
            entity(as[Json]) { query =>
              val result =
                viewCache.getProjectionBy[View](project.ref, id, projectionId).flatMap(runSearch(params, id, query))
              complete(result.runWithStatus(StatusCodes.OK))
            }
          }
        }
      },
      // Queries a projection view on the Sparql endpoint
      sparqlRoutes(
        s"/${config.http.prefix}/views/{org}/{project}/{id}/projections/{projectionId}/sparql",
        id,
        viewCache.getProjectionBy[View](project.ref, id, projectionId)
      ),
      // Retrieves statistics for a projection
      (get & pathPrefix("statistics") & pathEndOrSingleSlash) {
        operationName(s"/${config.http.prefix}/views/{org}/{project}/{id}/projections/{projectionId}/statistics") {
          hasPermission(read).apply {
            val result = OptionT(coordinator.projectionStats(id, projectionId)).toRight(notFound(id.ref))
            complete(result.value.runWithStatus(StatusCodes.OK))
          }
        }
      },
      // Retrieves offset for a projection
      (get & pathPrefix("offset") & pathEndOrSingleSlash) {
        operationName(s"/${config.http.prefix}/views/{org}/{project}/{id}/projections/{projectionId}/offset") {
          hasPermission(read).apply {
            val result = OptionT(coordinator.projectionOffset(id, projectionId)).toRight(notFound(id.ref))
            complete(result.value.runWithStatus(StatusCodes.OK))
          }
        }
      },
      // Delete offset from a projection. This triggers view restart with initial progress for selected projection
      (delete & pathPrefix("offset") & pathEndOrSingleSlash) {
        operationName(s"/${config.http.prefix}/views/{org}/{project}/{id}/projections/{projectionId}/offset") {
          val result: OptionT[Task, Offset] = for {
            offset <- OptionT(coordinator.projectionOffset(id, projectionId))
            _      <- OptionT(coordinator.restartProjection(id, projectionId))
          } yield offset match {
            case CompositeViewOffset(values) => CompositeViewOffset(values.map(_.map(_ => NoOffset)))
            case _                           => NoOffset
          }
          val eitherResult                  = result.toRight(notFound(id.ref)).value
          complete(eitherResult.runWithStatus(StatusCodes.OK))
        }
      }
    )

  private def routesAllSources(id: AbsoluteIri): Route =
    concat(
      // Retrieves statistics from all sources
      (get & pathPrefix("statistics") & pathEndOrSingleSlash) {
        operationName(s"/${config.http.prefix}/views/{org}/{project}/{id}/sources/_/statistics") {
          hasPermission(read).apply {
            val result = coordinator.sourceStats(id).map(toEitherQueryResults(id))
            complete(result.runWithStatus(StatusCodes.OK))
          }
        }
      },
      // Retrieves offset from all sources
      (get & pathPrefix("offset") & pathEndOrSingleSlash) {
        operationName(s"/${config.http.prefix}/views/{org}/{project}/{id}/sources/_/offset") {
          hasPermission(read).apply {
            val result = coordinator.sourceOffsets(id).map(toEitherQueryResults(id))
            complete(result.runWithStatus(StatusCodes.OK))
          }
        }
      }
    )

  private def routesSource(id: AbsoluteIri, sourceId: AbsoluteIri): Route =
    concat(
      // Retrieves statistics for a source
      (get & pathPrefix("statistics") & pathEndOrSingleSlash) {
        operationName(s"/${config.http.prefix}/views/{org}/{project}/{id}/sources/{sourceId}/statistics") {
          hasPermission(read).apply {
            val result = OptionT(coordinator.sourceStat(id, sourceId)).toRight(notFound(id.ref))
            complete(result.value.runWithStatus(StatusCodes.OK))
          }
        }
      },
      // Retrieves offset for a source
      (get & pathPrefix("offset") & pathEndOrSingleSlash) {
        operationName(s"/${config.http.prefix}/views/{org}/{project}/{id}/sources/{sourceId}/offset") {
          hasPermission(read).apply {
            val result = OptionT(coordinator.sourceOffset(id, sourceId)).toRight(notFound(id.ref))
            complete(result.value.runWithStatus(StatusCodes.OK))
          }
        }
      }
    )

  private def sparqlRoutes(operation: String, id: AbsoluteIri, view: => Task[Option[View]]): Route =
    (pathPrefix("sparql") & pathEndOrSingleSlash) {
      hasPermission(query).apply {
        concat(
          post {
            operationName(s"$operation/post") {
              entity(as[String]) { query =>
                complete(view.flatMap(runSearch(id, query)).runWithStatus(StatusCodes.OK))
              }
            }
          },
          (get & parameter("query")) { q =>
            operationName(s"$operation/get") {
              complete(view.flatMap(runSearch(id, q)).runWithStatus(StatusCodes.OK))
            }
          }
        )
      }
    }

  private def sparqlQuery(view: SparqlView, query: String): Task[SparqlResults] =
    clients.sparql.copy(namespace = view.index).queryRaw(query)

  private def runSearch(id: AbsoluteIri, query: String): Option[View] => Task[Either[Rejection, SparqlResults]] = {
    case Some(v: SparqlView)            => sparqlQuery(v, query).map(Right.apply)
    case Some(agg: AggregateSparqlView) =>
      val resultListF = agg.queryableViews[Task, SparqlView].flatMap { views =>
        Task.parSequenceUnordered(views.map(sparqlQuery(_, query)))
      }
      resultListF.map(list => Right(list.foldLeft(SparqlResults.empty)(_ ++ _)))
    case Some(comp: CompositeView)      =>
      val projectionViews = comp.projectionsBy[SparqlProjection].map(_.view)
      val resultListF     = Task.parSequenceUnordered(projectionViews.map(sparqlQuery(_, query)))
      resultListF.map(list => Right(list.foldLeft(SparqlResults.empty)(_ ++ _)))
    case _                              => Task.pure(Left(NotFound(id.ref)))
  }

  private def runProjectionsSearch(
      params: Uri.Query,
      id: AbsoluteIri,
      query: Json
  ): Option[CompositeView] => Task[Either[Rejection, Json]] = {
    case Some(view) => runSearchForIndices(params, query)(view.projectionsBy[ElasticSearchProjection].map(_.view.index))
    case _          => Task.pure(Left(NotFound(id.ref)))
  }

  private def runSearch(
      params: Uri.Query,
      id: AbsoluteIri,
      query: Json
  ): Option[View] => Task[Either[Rejection, Json]] = {
    case Some(v: ElasticSearchView)            =>
      clients.elasticSearch.searchRaw(query, Set(v.index), params).map(Right.apply)
    case Some(agg: AggregateElasticSearchView) =>
      agg.queryableViews[Task, ElasticSearchView].flatMap(v => runSearchForIndices(params, query)(v.map(_.index)))
    case _                                     =>
      Task.pure(Left(NotFound(id.ref)))
  }

  private def runSearchForIndices(params: Uri.Query, query: Json)(indices: Set[String]): Task[Either[Rejection, Json]] =
    indices match {
      case indices if indices.isEmpty => Task.pure[Either[Rejection, Json]](Right(emptyEsList))
      case indices                    => clients.elasticSearch.searchRaw(query, indices, params).map(Right.apply)
    }

  private def toEitherQueryResults[A](
      id: AbsoluteIri
  ): Option[Set[IdentifiedProgress[A]]] => Either[Rejection, ListResults[A]] =
    _.toRight(notFound(id.ref)).map(toQueryResults)

  private def toQueryResults[A](values: Set[IdentifiedProgress[A]]): ListResults[A] =
    UnscoredQueryResults(values.size.toLong, values.toList.sorted.map(UnscoredQueryResult(_)))
}

object ViewRoutes {

  type ListResults[A] = QueryResults[IdentifiedProgress[A]]

  implicit private[routes] val encoderOffset: Encoder[Offset] =
    Encoder
      .instance[Offset] {
        case Sequence(value)            =>
          Json.obj(
            "@type" -> nxv.SequenceBasedOffset.prefix.asJson,
            "value" -> value.asJson
          )
        case t: TimeBasedUUID           =>
          Json.obj(
            "@type"   -> nxv.TimeBasedOffset.prefix.asJson,
            "value"   -> t.value.toString.asJson,
            "instant" -> t.asInstant.asJson
          )
        case CompositeViewOffset(value) =>
          Json.obj(
            "@type"  -> nxv.CompositeViewOffset.prefix.asJson,
            "values" -> value.asJson.removeNestedKeys("@context")
          )
        case NoOffset                   =>
          Json.obj("@type" -> nxv.NoOffset.prefix.asJson)
        case _                          =>
          Json.obj()
      }
      .mapJson(_.addContext(offsetCtxUri))

  implicit private[routes] def encoderListResultsOffset: Encoder[ListResults[Offset]] =
    qrsEncoderLowPrio[IdOffset]
      .mapJson(_.removeNestedKeys("@context").addContext(offsetCtxUri).addContext(searchCtxUri))

  implicit private[routes] def encoderListResultsStatistics: Encoder[ListResults[ViewStatistics]] =
    qrsEncoderLowPrio[IdStats]
      .mapJson(_.removeNestedKeys("@context").addContext(statisticsCtxUri).addContext(searchCtxUri))

}
