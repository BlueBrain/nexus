package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.routes

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.StatusCodes.Created
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server._
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewRejection._
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model._
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.routes.ElasticSearchViewsRoutes.responseFieldsElasticSearchRejections
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.{ElasticSearchViews, ElasticSearchViewsQuery}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.circe.CirceUnmarshalling
import ch.epfl.bluebrain.nexus.delta.sdk.directives.AuthDirectives
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaDirectives._
import ch.epfl.bluebrain.nexus.delta.sdk.instances.OffsetInstances._
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.HttpResponseFields
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.RdfRejectionHandler._
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.routes.{JsonSource, Tag, Tags}
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.PaginationConfig
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, IdSegment, TagLabel}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sourcing.config.ExternalIndexingConfig
import io.circe.{Json, JsonObject}
import kamon.instrumentation.akka.http.TracingDirectives.operationName
import monix.execution.Scheduler

/**
  * The elasticsearch views routes
  *
  * @param identities the identity module
  * @param acls       the ACLs module
  * @param projects   the projects module
  * @param views      the elasticsearch views operations bundle
  * @param viewsQuery the elasticsearch views query operations bundle
  * @param progresse the statistics of the progresses for the elasticsearch views
  */
final class ElasticSearchViewsRoutes(
    identities: Identities,
    acls: Acls,
    projects: Projects,
    views: ElasticSearchViews,
    viewsQuery: ElasticSearchViewsQuery,
    progresse: ProgressesStatistics
)(implicit
    baseUri: BaseUri,
    paginationConfig: PaginationConfig,
    config: ExternalIndexingConfig,
    s: Scheduler,
    cr: RemoteContextResolution,
    ordering: JsonKeyOrdering
) extends AuthDirectives(identities, acls)
    with CirceUnmarshalling
    with ElasticSearchViewsDirectives {

  import baseUri.prefixSegment

  def routes: Route =
    baseUriPrefix(baseUri.prefix) {
      extractCaller { implicit caller =>
        pathPrefix("views") {
          // TODO: SSE for all view events/all view events in an org and all view events in a project needs to happen
          // using some sort of globalEventExchange for SSEs that includes elasticsearch, blazegraph and composite events
          projectRef(projects).apply { ref =>
            concat(
              (pathEndOrSingleSlash & operationName(s"$prefixSegment/views/{org}/{project}")) {
                concat(
                  // List all views
                  get {
                    authorizeFor(AclAddress.Project(ref), permissions.read).apply {
                      complete("OK") // TODO: implement
                    }
                  },
                  // Create an elasticsearch view without id segment
                  (post & pathEndOrSingleSlash & noParameter("rev") & entity(as[Json])) { source =>
                    authorizeFor(AclAddress.Project(ref), permissions.write).apply {
                      emit(Created, views.create(ref, source).map(_.void))
                    }
                  }
                )
              },
              idSegment { id =>
                concat(
                  pathEndOrSingleSlash {
                    operationName(s"$prefixSegment/views/{org}/{project}/{id}") {
                      concat(
                        // Create or update an elasticsearch view
                        put {
                          authorizeFor(AclAddress.Project(ref), permissions.write).apply {
                            (parameter("rev".as[Long].?) & pathEndOrSingleSlash & entity(as[Json])) {
                              case (None, source)      =>
                                // Create an elasticsearch view with id segment
                                emit(Created, views.create(id, ref, source).map(_.void))
                              case (Some(rev), source) =>
                                // Update a view
                                emit(views.update(id, ref, rev, source).map(_.void))
                            }
                          }
                        },
                        // Deprecate an elasticsearch view
                        (delete & parameter("rev".as[Long])) { rev =>
                          authorizeFor(AclAddress.Project(ref), permissions.write).apply {
                            emit(views.deprecate(id, ref, rev).map(_.void))
                          }
                        },
                        // Fetch an elasticsearch view
                        get {
                          fetch(id, ref)
                        }
                      )
                    }
                  },
                  // Fetch an elasticsearch view statistics
                  (pathPrefix("statistics") & get & pathEndOrSingleSlash) {
                    operationName(s"$prefixSegment/views/{org}/{project}/{id}/statistics") {
                      authorizeFor(AclAddress.Project(ref), permissions.read).apply {
                        emit(views.fetchIndexingView(id, ref).flatMap(v => progresse.statistics(ref, v.projectionId)))
                      }
                    }
                  },
                  // Manage an elasticsearch view offset
                  (pathPrefix("offset") & pathEndOrSingleSlash) {
                    operationName(s"$prefixSegment/views/{org}/{project}/{id}/offset") {
                      concat(
                        // Fetch an elasticsearch view offset
                        (get & authorizeFor(AclAddress.Project(ref), permissions.read)) {
                          emit(views.fetchIndexingView(id, ref).flatMap(v => progresse.offset(v.projectionId)))
                        },
                        // Remove an elasticsearch view offset (restart the view)
                        (delete & authorizeFor(AclAddress.Project(ref), permissions.write)) {
                          complete("OK") // TODO: implement
                        }
                      )
                    }
                  },
                  // Query an elasticsearch view
                  (pathPrefix("_search") & post & pathEndOrSingleSlash) {
                    operationName(s"$prefixSegment/views/{org}/{project}/{id}/_search") {
                      (paginated & extract(_.request.uri.query()) & sortList & entity(as[JsonObject])) {
                        (page, qp, sort, query) =>
                          emit(viewsQuery.query(id, ref, page, query, qp, sort))
                      }
                    }
                  },
                  // Fetch an elasticsearch view original source
                  (pathPrefix("source") & get & pathEndOrSingleSlash) {
                    operationName(s"$prefixSegment/views/{org}/{project}/{id}/source") {
                      fetchMap(id, ref, resource => JsonSource(resource.value.source, resource.value.id))
                    }
                  },
                  (pathPrefix("tags") & pathEndOrSingleSlash) {
                    operationName(s"$prefixSegment/views/{org}/{project}/{id}/tags") {
                      concat(
                        // Fetch an elasticsearch view tags
                        get {
                          fetchMap(id, ref, resource => Tags(resource.value.tags))
                        },
                        // Tag an elasticsearch view
                        (post & parameter("rev".as[Long])) { rev =>
                          authorizeFor(AclAddress.Project(ref), permissions.write).apply {
                            entity(as[Tag]) { case Tag(tagRev, tag) =>
                              emit(Created, views.tag(id, ref, tag, tagRev, rev).map(_.void))
                            }
                          }
                        }
                      )
                    }
                  }
                )
              }
            )
          }
        }
      }
    }

  private def fetch(id: IdSegment, ref: ProjectRef)(implicit caller: Caller) =
    fetchMap(id, ref, identity)

  private def fetchMap[A: JsonLdEncoder](
      id: IdSegment,
      ref: ProjectRef,
      f: ViewResource => A
  )(implicit caller: Caller) =
    authorizeFor(AclAddress.Project(ref), permissions.read).apply {
      (parameter("rev".as[Long].?) & parameter("tag".as[TagLabel].?)) {
        case (Some(_), Some(_)) => emit(simultaneousTagAndRevRejection)
        case (Some(rev), _)     => emit(views.fetchAt(id, ref, rev).map(f))
        case (_, Some(tag))     => emit(views.fetchBy(id, ref, tag).map(f))
        case _                  => emit(views.fetch(id, ref).map(f))
      }
    }
}

object ElasticSearchViewsRoutes {

  /**
    * @return the [[Route]] for elasticsearch views
    */
  def apply(
      identities: Identities,
      acls: Acls,
      projects: Projects,
      views: ElasticSearchViews,
      viewsQuery: ElasticSearchViewsQuery,
      progresses: ProgressesStatistics
  )(implicit
      baseUri: BaseUri,
      paginationConfig: PaginationConfig,
      config: ExternalIndexingConfig,
      s: Scheduler,
      cr: RemoteContextResolution,
      ordering: JsonKeyOrdering
  ): Route = new ElasticSearchViewsRoutes(identities, acls, projects, views, viewsQuery, progresses).routes

  implicit val responseFieldsElasticSearchRejections: HttpResponseFields[ElasticSearchViewRejection] =
    HttpResponseFields {
      case RevisionNotFound(_, _)                 => StatusCodes.NotFound
      case TagNotFound(_)                         => StatusCodes.NotFound
      case ViewNotFound(_, _)                     => StatusCodes.NotFound
      case ViewAlreadyExists(_, _)                => StatusCodes.Conflict
      case IncorrectRev(_, _)                     => StatusCodes.Conflict
      case WrappedProjectRejection(rej)           => rej.status
      case UnexpectedInitialState(_, _)           => StatusCodes.InternalServerError
      case AuthorizationFailed                    => StatusCodes.Forbidden
      case WrappedElasticSearchClientError(error) => error.errorCode.getOrElse(StatusCodes.InternalServerError)
      case _                                      => StatusCodes.BadRequest
    }

}
