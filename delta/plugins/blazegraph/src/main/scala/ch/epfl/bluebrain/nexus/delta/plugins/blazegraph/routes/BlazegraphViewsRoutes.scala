package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.routes

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.StatusCodes.Created
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.SparqlQuery
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphView._
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphViewRejection._
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.{permissions, BlazegraphViewRejection, ViewResource}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.routes.BlazegraphViewsRoutes.responseFieldsBlazegraphViews
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.{BlazegraphViews, BlazegraphViewsQuery}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.circe.CirceUnmarshalling
import ch.epfl.bluebrain.nexus.delta.sdk.directives.AuthDirectives
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaDirectives
import ch.epfl.bluebrain.nexus.delta.sdk.instances.OffsetInstances._
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.HttpResponseFields
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.HttpResponseFields.{responseFieldsOrganizations, responseFieldsProjects}
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.RdfRejectionHandler._
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.routes.{JsonSource, Tag, Tags}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, IdSegment, TagLabel}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.{Acls, Identities, ProgressesStatistics, Projects}
import ch.epfl.bluebrain.nexus.delta.sourcing.config.ExternalIndexingConfig
import io.circe.Json
import kamon.instrumentation.akka.http.TracingDirectives.operationName
import monix.execution.Scheduler

/**
  * The Blazegraph views routes
  *
  * @param views      the blazegraph views operations bundle
  * @param identities the identity module
  * @param acls       the ACLs module
  * @param projects   the projects module
  */
class BlazegraphViewsRoutes(
    views: BlazegraphViews,
    viewsQuery: BlazegraphViewsQuery,
    identities: Identities,
    acls: Acls,
    projects: Projects,
    progresses: ProgressesStatistics
)(implicit
    baseUri: BaseUri,
    s: Scheduler,
    config: ExternalIndexingConfig,
    cr: RemoteContextResolution,
    ordering: JsonKeyOrdering
) extends AuthDirectives(identities, acls)
    with CirceUnmarshalling
    with DeltaDirectives
    with BlazegraphViewsDirectives {

  import baseUri.prefixSegment
  def routes: Route =
    baseUriPrefix(baseUri.prefix) {
      extractCaller { implicit caller =>
        pathPrefix("views") {
          projectRef(projects).apply { implicit ref =>
            // Create a view without id segment
            concat(
              (post & entity(as[Json]) & noParameter("rev") & pathEndOrSingleSlash & operationName(
                s"$prefixSegment/views/{org}/{project}"
              )) { source =>
                authorizeFor(AclAddress.Project(ref), permissions.write).apply {
                  emit(Created, views.create(ref, source).map(_.void))
                }
              },
              idSegment { id =>
                concat(
                  (pathEndOrSingleSlash & operationName(s"$prefixSegment/views/{org}/{project}/{id}")) {
                    concat(
                      put {
                        authorizeFor(AclAddress.Project(ref), permissions.write).apply {
                          (parameter("rev".as[Long].?) & pathEndOrSingleSlash & entity(as[Json])) {
                            case (None, source)      =>
                              // Create a view with id segment
                              emit(Created, views.create(id, ref, source).map(_.void))
                            case (Some(rev), source) =>
                              // Update a view
                              emit(views.update(id, ref, rev, source).map(_.void))
                          }
                        }
                      },
                      (delete & parameter("rev".as[Long])) { rev =>
                        // Deprecate a view
                        authorizeFor(AclAddress.Project(ref), permissions.write).apply {
                          emit(views.deprecate(id, ref, rev).map(_.void))
                        }
                      },
                      // Fetch a view
                      get {
                        fetch(id, ref)
                      }
                    )
                  },
                  // Query a blazegraph view
                  (pathPrefix("sparql") & pathEndOrSingleSlash) {
                    concat(
                      //Query using GET and `query` parameter
                      (get & parameter("query".as[SparqlQuery])) { query =>
                        emit(viewsQuery.query(id, ref, query))
                      },
                      //Query using POST and request body
                      (post & entity(as[SparqlQuery])) { query =>
                        emit(viewsQuery.query(id, ref, query))
                      }
                    )
                  },
                  // Fetch a blazegraph view statistics
                  (pathPrefix("statistics") & get & pathEndOrSingleSlash) {
                    operationName(s"$prefixSegment/views/{org}/{project}/{id}/statistics") {
                      authorizeFor(AclAddress.Project(ref), permissions.read).apply {
                        emit(views.fetchIndexingView(id, ref).flatMap(v => progresses.statistics(ref, v.projectionId)))
                      }
                    }
                  },
                  // Manage an blazegraph view offset
                  (pathPrefix("offset") & pathEndOrSingleSlash) {
                    operationName(s"$prefixSegment/views/{org}/{project}/{id}/offset") {
                      concat(
                        // Fetch a blazegraph view offset
                        (get & authorizeFor(AclAddress.Project(ref), permissions.read)) {
                          emit(views.fetchIndexingView(id, ref).flatMap(v => progresses.offset(v.projectionId)))
                        }
                      )
                    }
                  },
                  (pathPrefix("tags") & pathEndOrSingleSlash & operationName(
                    s"$prefixSegment/views/{org}/{project}/{id}/tags"
                  )) {
                    concat(
                      // Fetch tags for a view
                      get {
                        fetchMap(id, ref, resource => Tags(resource.value.tags))
                      },
                      // Tag a view
                      (post & parameter("rev".as[Long])) { rev =>
                        authorizeFor(AclAddress.Project(ref), permissions.write).apply {
                          entity(as[Tag]) { case Tag(tagRev, tag) =>
                            emit(Created, views.tag(id, ref, tag, tagRev, rev).map(_.void))
                          }
                        }
                      }
                    )
                  },
                  // Fetch a view original source
                  (pathPrefix("source") & get & pathEndOrSingleSlash & operationName(
                    s"$prefixSegment/views/{org}/{project}/{id}/source"
                  )) {
                    fetchMap(
                      id,
                      ref,
                      res => JsonSource(res.value.source, res.value.id)
                    )
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

  private def fetchMap[A: JsonLdEncoder](id: IdSegment, ref: ProjectRef, f: ViewResource => A)(implicit
      caller: Caller
  ): Route =
    authorizeFor(AclAddress.Project(ref), permissions.read).apply {
      (parameter("rev".as[Long].?) & parameter("tag".as[TagLabel].?)) {
        case (Some(_), Some(_)) => emit(simultaneousTagAndRevRejection)
        case (Some(rev), _)     => emit(views.fetchAt(id, ref, rev).map(f))
        case (_, Some(tag))     => emit(views.fetchBy(id, ref, tag).map(f))
        case _                  => emit(views.fetch(id, ref).map(f))
      }
    }
}

object BlazegraphViewsRoutes {

  /**
    * @return the [[Route]] for BlazegraphViews
    */
  def apply(
      views: BlazegraphViews,
      viewsQuery: BlazegraphViewsQuery,
      identities: Identities,
      acls: Acls,
      projects: Projects,
      progresses: ProgressesStatistics
  )(implicit
      baseUri: BaseUri,
      s: Scheduler,
      config: ExternalIndexingConfig,
      cr: RemoteContextResolution,
      ordering: JsonKeyOrdering
  ): Route = {
    new BlazegraphViewsRoutes(views, viewsQuery, identities, acls, projects, progresses).routes
  }

  implicit val responseFieldsBlazegraphViews: HttpResponseFields[BlazegraphViewRejection] =
    HttpResponseFields {
      case RevisionNotFound(_, _)            => StatusCodes.NotFound
      case TagNotFound(_)                    => StatusCodes.NotFound
      case ViewNotFound(_, _)                => StatusCodes.NotFound
      case ViewAlreadyExists(_, _)           => StatusCodes.Conflict
      case IncorrectRev(_, _)                => StatusCodes.Conflict
      case UnexpectedInitialState(_, _)      => StatusCodes.InternalServerError
      case WrappedProjectRejection(rej)      => rej.status
      case WrappedOrganizationRejection(rej) => rej.status
      case _                                 => StatusCodes.BadRequest
    }

}
