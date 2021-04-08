package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.routes

import akka.http.scaladsl.model.StatusCodes.Created
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.persistence.query.{NoOffset, Offset}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.CompositeViews
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewRejection._
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model._
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.routes.CompositeViewsRoutes.{RestartProjections, RestartView}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.circe.CirceUnmarshalling
import ch.epfl.bluebrain.nexus.delta.sdk.directives.{AuthDirectives, DeltaDirectives}
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.RdfRejectionHandler._
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.routes.{JsonSource, Tag, Tags}
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults.searchResultsJsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, IdSegment, TagLabel}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.{Acls, Identities, ProgressesStatistics, Projects}
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.ProjectionId
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.ProjectionId.CompositeViewProjectionId
import io.circe.Json
import kamon.instrumentation.akka.http.TracingDirectives.operationName
import monix.bio.UIO
import monix.execution.Scheduler

/**
  * Composite views routes.
  */
class CompositeViewsRoutes(
    identities: Identities,
    acls: Acls,
    projects: Projects,
    views: CompositeViews,
    restartView: RestartView,
    restartProjections: RestartProjections,
    progresses: ProgressesStatistics
)(implicit
    baseUri: BaseUri,
    s: Scheduler,
    cr: RemoteContextResolution,
    ordering: JsonKeyOrdering
) extends AuthDirectives(identities, acls)
    with DeltaDirectives
    with CirceUnmarshalling {

  import baseUri.prefixSegment

  implicit private val offsetsSearchJsonLdEncoder: JsonLdEncoder[SearchResults[CompositeOffset]] =
    searchResultsJsonLdEncoder(ContextValue(contexts.statistics))

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
                (pathPrefix("tags") & pathEndOrSingleSlash) {
                  operationName(s"$prefixSegment/views/{org}/{project}/{id}/tags") {
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
                  }
                },
                // Fetch a view original source
                (pathPrefix("source") & get & pathEndOrSingleSlash) {
                  operationName(s"$prefixSegment/views/{org}/{project}/{id}/source") {
                    fetchMap(
                      id,
                      ref,
                      res => JsonSource(res.value.source, res.value.id)
                    )
                  }
                },
                // Manage composite view offsets
                (pathPrefix("offset") & pathEndOrSingleSlash) {
                  operationName(s"$prefixSegment/views/{org}/{project}/{id}/offset") {
                    concat(
                      // Fetch all composite view offsets
                      (get & authorizeFor(AclAddress.Project(ref), permissions.read)) {
                        emit(views.fetch(id, ref).flatMap(fetchOffsets).rejectWhen(decodingFailedOrViewNotFound))
                      },
                      // Remove all composite view offsets (restart the view)
                      (delete & authorizeFor(AclAddress.Project(ref), permissions.write)) {
                        emit(
                          views
                            .fetch(id, ref)
                            .flatMap(v => restartView(v.id, v.value.project) >> resetOffsets(v))
                            .rejectWhen(decodingFailedOrViewNotFound)
                        )
                      }
                    )
                  }
                },
                // Manage composite view projection offsets
                pathPrefix("projections") {
                  concat(
                    (pathPrefix("_") & pathPrefix("offset") & pathEndOrSingleSlash) {
                      operationName(s"$prefixSegment/views/{org}/{project}/{id}/projections/_/offset") {
                        concat(
                          // Fetch all composite view projection offsets
                          (get & authorizeFor(AclAddress.Project(ref), permissions.read)) {
                            emit(views.fetch(id, ref).flatMap(fetchOffsets))
                          },
                          // Remove all composite view projection offsets
                          (delete & authorizeFor(AclAddress.Project(ref), permissions.write)) {
                            emit(
                              views.fetch(id, ref).flatMap { v =>
                                val projectionIds = CompositeViews.projectionIds(v.value, v.rev).map(_._3)
                                restartProjections(v.id, v.value.project, projectionIds) >> resetOffsets(v)
                              }
                            )
                          }
                        )
                      }
                    },
                    (idSegment & pathPrefix("offset") & pathEndOrSingleSlash) { projectionId =>
                      operationName(s"$prefixSegment/views/{org}/{project}/{id}/projections/{projectionId}/offset") {
                        concat(
                          // Fetch a composite view projection offset
                          (get & authorizeFor(AclAddress.Project(ref), permissions.read)) {
                            emit(views.fetchProjection(id, projectionId, ref).flatMap(fetchProjectionOffsets))
                          },
                          // Remove a composite view projection offset
                          (delete & authorizeFor(AclAddress.Project(ref), permissions.write)) {
                            emit(
                              views
                                .fetchProjection(id, projectionId, ref)
                                .flatMap { v =>
                                  val (view, projection) = v.value
                                  val projectionIds      = CompositeViews.projectionIds(view, projection, v.rev).map(_._2)
                                  restartProjections(v.id, ref, projectionIds) >> resetProjectionOffsets(v)
                                }
                            )
                          }
                        )
                      }
                    }
                  )
                }
              )
            }
          )
        }
      }
    }
  }

  private def fetchOffsets(viewRes: ViewResource) =
    offsets(CompositeViews.projectionIds(viewRes.value, viewRes.rev))(progresses.offset)

  private def resetOffsets(viewRes: ViewResource) =
    offsets(CompositeViews.projectionIds(viewRes.value, viewRes.rev))(_ => UIO.pure(NoOffset))

  private def fetchProjectionOffsets(viewRes: ViewProjectionResource) = {
    val (view, projection) = viewRes.value
    offsets(CompositeViews.projectionIds(view, projection, viewRes.rev).map { case (sId, compositeProjectionId) =>
      (sId, projection.id, compositeProjectionId)
    })(progresses.offset)
  }

  private def resetProjectionOffsets(viewRes: ViewProjectionResource) = {
    val (view, projection) = viewRes.value
    offsets(CompositeViews.projectionIds(view, projection, viewRes.rev).map { case (sId, compositeProjectionId) =>
      (sId, projection.id, compositeProjectionId)
    })(_ => UIO.pure(NoOffset))
  }

  private def offsets(
      compositeProjectionIds: Set[(Iri, Iri, CompositeViewProjectionId)]
  )(fetchOffset: ProjectionId => UIO[Offset]): UIO[SearchResults[CompositeOffset]] =
    UIO
      .traverse(compositeProjectionIds) { case (sId, pId, projection) =>
        fetchOffset(projection).map(offset => CompositeOffset(sId, pId, offset))
      }
      .map[SearchResults[CompositeOffset]](list => SearchResults(list.size.toLong, list.sorted))

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

  type RestartView = (Iri, ProjectRef) => UIO[Unit]

  type RestartProjections = (Iri, ProjectRef, Set[CompositeViewProjectionId]) => UIO[Unit]

  /**
    * @return the [[Route]] for composite views.
    */
  def apply(
      identities: Identities,
      acls: Acls,
      projects: Projects,
      views: CompositeViews,
      restartView: RestartView,
      restartProjections: RestartProjections,
      progresses: ProgressesStatistics
  )(implicit
      baseUri: BaseUri,
      s: Scheduler,
      cr: RemoteContextResolution,
      ordering: JsonKeyOrdering
  ): Route =
    new CompositeViewsRoutes(identities, acls, projects, views, restartView, restartProjections, progresses).routes
}
