package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.routes

import akka.http.scaladsl.model.StatusCodes.Created
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.CompositeViews
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewRejection._
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.{permissions, CompositeViewRejection, ViewResource}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.circe.CirceUnmarshalling
import ch.epfl.bluebrain.nexus.delta.sdk.directives.{AuthDirectives, DeltaDirectives}
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
  * Composite views routes.
  */
class CompositeViewsRoutes(
    identities: Identities,
    acls: Acls,
    projects: Projects,
    views: CompositeViews
)(implicit
    baseUri: BaseUri,
    s: Scheduler,
    cr: RemoteContextResolution,
    ordering: JsonKeyOrdering
) extends AuthDirectives(identities, acls)
    with DeltaDirectives
    with CirceUnmarshalling {

  import baseUri.prefixSegment

  def routes: Route = (baseUriPrefix(baseUri.prefix) & replaceUriOnUnderscore("views")) {
    extractCaller { implicit caller =>
      pathPrefix("views") {
        projectRef(projects).apply { implicit ref =>
          concat(
            //Create a view without id segment
            (post & entity(as[Json]) & noParameter("rev") & pathEndOrSingleSlash & operationName(
              s"$prefixSegment/views/{org}/{project}"
            )) { source =>
              authorizeFor(AclAddress.Project(ref), permissions.write).apply {
                emit(
                  Created,
                  views.create(ref, source).mapValue(_.metadata).rejectWhen(decodingFailedOrViewNotFound)
                )
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
                            emit(
                              Created,
                              views
                                .create(id, ref, source)
                                .mapValue(_.metadata)
                                .rejectWhen(decodingFailedOrViewNotFound)
                            )
                          case (Some(rev), source) =>
                            // Update a view
                            emit(
                              views
                                .update(id, ref, rev, source)
                                .mapValue(_.metadata)
                                .rejectWhen(decodingFailedOrViewNotFound)
                            )
                        }
                      }
                    },
                    //Deprecate a view
                    (delete & parameter("rev".as[Long])) { rev =>
                      authorizeFor(AclAddress.Project(ref), permissions.write).apply {
                        emit(views.deprecate(id, ref, rev).mapValue(_.metadata).rejectOn[ViewNotFound])
                      }
                    },
                    // Fetch a view
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
                          emit(
                            Created,
                            views.tag(id, ref, tag, tagRev, rev).mapValue(_.metadata).rejectOn[ViewNotFound]
                          )
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
        case (Some(rev), _)     => emit(views.fetchAt(id, ref, rev).map(f).rejectOn[ViewNotFound])
        case (_, Some(tag))     => emit(views.fetchBy(id, ref, tag).map(f).rejectOn[ViewNotFound])
        case _                  => emit(views.fetch(id, ref).map(f).rejectOn[ViewNotFound])
      }
    }

  private val decodingFailedOrViewNotFound: PartialFunction[CompositeViewRejection, Boolean] = {
    case _: DecodingFailed | _: ViewNotFound | _: InvalidJsonLdFormat => true
  }

}

object CompositeViewsRoutes {

  /**
    * @return the [[Route]] for composite views.
    */
  def apply(
      identities: Identities,
      acls: Acls,
      projects: Projects,
      views: CompositeViews
  )(implicit
      baseUri: BaseUri,
      s: Scheduler,
      cr: RemoteContextResolution,
      ordering: JsonKeyOrdering
  ): Route = new CompositeViewsRoutes(identities, acls, projects, views).routes
}
