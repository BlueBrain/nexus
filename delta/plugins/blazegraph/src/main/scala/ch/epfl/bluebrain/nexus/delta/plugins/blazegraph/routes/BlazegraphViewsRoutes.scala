package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.routes

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.StatusCodes.Created
import akka.http.scaladsl.server.Directives.{pathPrefix, _}
import akka.http.scaladsl.server.Route
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.BlazegraphViews
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphViewRejection.{IncorrectRev, RevisionNotFound, TagNotFound, UnexpectedInitialState, ViewAlreadyExists, ViewNotFound, WrappedOrganizationRejection, WrappedProjectRejection}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.{permissions, BlazegraphViewRejection, ViewResource}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.routes.BlazegraphViewsRoutes.responseFieldsBlazegraphViews
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.circe.CirceUnmarshalling
import ch.epfl.bluebrain.nexus.delta.sdk.directives.AuthDirectives
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaDirectives._
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.HttpResponseFields
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.HttpResponseFields.{responseFieldsOrganizations, responseFieldsProjects}
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.RdfRejectionHandler._
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.routes.{JsonSource, Tag, Tags}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, IdSegment, TagLabel}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.{Acls, Identities, Projects}
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
    identities: Identities,
    acls: Acls,
    projects: Projects
)(implicit
    baseUri: BaseUri,
    s: Scheduler,
    cr: RemoteContextResolution,
    ordering: JsonKeyOrdering
) extends AuthDirectives(identities, acls)
    with CirceUnmarshalling {

  import baseUri.prefixSegment
  def routes: Route =
    baseUriPrefix(baseUri.prefix) {
      extractCaller { implicit caller =>
        pathPrefix("views") {
          projectRef(projects).apply { implicit ref =>
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
                        authorizeFor(AclAddress.Project(ref), permissions.write).apply {
                          emit(views.deprecate(id, ref, rev).map(_.void))
                        }
                      },
                      get {
                        fetch(id, ref)
                      }
                    )
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
        case (Some(rev), _)     => emit(views.fetchAt(id, ref, rev).leftWiden[BlazegraphViewRejection].map(f))
        case (_, Some(tag))     => emit(views.fetchBy(id, ref, tag).leftWiden[BlazegraphViewRejection].map(f))
        case _                  => emit(views.fetch(id, ref).leftWiden[BlazegraphViewRejection].map(f))
      }
    }
}

object BlazegraphViewsRoutes {

  /**
    * @return the [[Route]] for BlazegraphViews
    */
  def apply(
      views: BlazegraphViews,
      identities: Identities,
      acls: Acls,
      projects: Projects
  )(implicit
      baseUri: BaseUri,
      s: Scheduler,
      cr: RemoteContextResolution,
      ordering: JsonKeyOrdering
  ): Route = {
    new BlazegraphViewsRoutes(views, identities, acls, projects).routes
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
