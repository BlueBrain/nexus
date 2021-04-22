package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.routes

import akka.http.scaladsl.model.StatusCodes.Created
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.persistence.query.{NoOffset, Offset}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.SparqlQuery
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.routes.BlazegraphViewsDirectives
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewRejection._
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model._
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.routes.CompositeViewsRoutes.{RestartProjections, RestartView}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.{BlazegraphQuery, CompositeViews, ElasticSearchQuery}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.routes.ElasticSearchViewsDirectives
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.circe.CirceUnmarshalling
import ch.epfl.bluebrain.nexus.delta.sdk.directives.{AuthDirectives, DeltaDirectives}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.routes.{Tag, Tags}
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults.searchResultsJsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, IdSegment}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.{Acls, Identities, ProgressesStatistics, Projects}
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.ProjectionId
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.ProjectionId.CompositeViewProjectionId
import io.circe.{Json, JsonObject}
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
    progresses: ProgressesStatistics,
    blazegraphQuery: BlazegraphQuery,
    elasticSearchQuery: ElasticSearchQuery
)(implicit
    baseUri: BaseUri,
    s: Scheduler,
    cr: RemoteContextResolution,
    ordering: JsonKeyOrdering
) extends AuthDirectives(identities, acls)
    with DeltaDirectives
    with CirceUnmarshalling
    with BlazegraphViewsDirectives
    with ElasticSearchViewsDirectives {

  import baseUri.prefixSegment

  implicit private val offsetsSearchJsonLdEncoder: JsonLdEncoder[SearchResults[ProjectionOffset]] =
    searchResultsJsonLdEncoder(ContextValue(contexts.offset))

  implicit private val statisticsSearchJsonLdEncoder: JsonLdEncoder[SearchResults[ProjectionStatistics]] =
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
              authorizeFor(ref, permissions.write).apply {
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
                      authorizeFor(ref, permissions.write).apply {
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
                      authorizeFor(ref, permissions.write).apply {
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
                        authorizeFor(ref, permissions.write).apply {
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
                    fetchSource(id, ref, _.value.source)
                  }
                },
                // Manage composite view offsets
                (pathPrefix("offset") & pathEndOrSingleSlash) {
                  operationName(s"$prefixSegment/views/{org}/{project}/{id}/offset") {
                    concat(
                      // Fetch all composite view offsets
                      (get & authorizeFor(ref, permissions.read)) {
                        emit(views.fetch(id, ref).flatMap(fetchOffsets).rejectWhen(decodingFailedOrViewNotFound))
                      },
                      // Remove all composite view offsets (restart the view)
                      (delete & authorizeFor(ref, permissions.write)) {
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
                // Fetch composite view statistics
                (get & pathPrefix("statistics") & pathEndOrSingleSlash) {
                  operationName(s"$prefixSegment/views/{org}/{project}/{id}/statistics") {
                    authorizeFor(ref, permissions.read).apply {
                      emit(views.fetch(id, ref).flatMap(fetchStatistics))
                    }
                  }
                },
                pathPrefix("projections") {
                  concat(
                    // Manage all views' projections offsets
                    (pathPrefix("_") & pathPrefix("offset") & pathEndOrSingleSlash) {
                      operationName(s"$prefixSegment/views/{org}/{project}/{id}/projections/_/offset") {
                        concat(
                          // Fetch all composite view projection offsets
                          (get & authorizeFor(ref, permissions.read)) {
                            emit(views.fetch(id, ref).flatMap(fetchOffsets))
                          },
                          // Remove all composite view projection offsets
                          (delete & authorizeFor(ref, permissions.write)) {
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
                    // Fetch all views' projections statistics
                    (get & pathPrefix("_") & pathPrefix("statistics") & pathEndOrSingleSlash) {
                      operationName(s"$prefixSegment/views/{org}/{project}/{id}/projections/_/statistics") {
                        authorizeFor(ref, permissions.read).apply {
                          emit(views.fetch(id, ref).flatMap(fetchStatistics))
                        }
                      }
                    },
                    // Manage a views' projection offset
                    (idSegment & pathPrefix("offset") & pathEndOrSingleSlash) { projectionId =>
                      operationName(s"$prefixSegment/views/{org}/{project}/{id}/projections/{projectionId}/offset") {
                        concat(
                          // Fetch a composite view projection offset
                          (get & authorizeFor(ref, permissions.read)) {
                            emit(views.fetchProjection(id, projectionId, ref).flatMap(fetchProjectionOffsets))
                          },
                          // Remove a composite view projection offset
                          (delete & authorizeFor(ref, permissions.write)) {
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
                    },
                    // Fetch a views' projection statistics
                    (get & idSegment & pathPrefix("statistics") & pathEndOrSingleSlash) { projectionId =>
                      operationName(
                        s"$prefixSegment/views/{org}/{project}/{id}/projections/{projectionId}/statistics"
                      ) {
                        authorizeFor(ref, permissions.read).apply {
                          emit(views.fetchProjection(id, projectionId, ref).flatMap(fetchProjectionStatistics))
                        }
                      }
                    },
                    // Query all composite views' sparql projections namespaces
                    (pathPrefix("_") & pathPrefix("sparql") & pathEndOrSingleSlash) {
                      operationName(s"$prefixSegment/views/{org}/{project}/{id}/projections/_/sparql") {
                        concat(
                          (get & parameter("query".as[SparqlQuery])) { query =>
                            emit(blazegraphQuery.queryProjections(id, ref, query))
                          },
                          (post & entity(as[SparqlQuery])) { query =>
                            emit(blazegraphQuery.queryProjections(id, ref, query))
                          }
                        )
                      }
                    },
                    // Query a composite views' sparql projection namespace
                    (idSegment & pathPrefix("sparql") & pathEndOrSingleSlash) { projectionId =>
                      operationName(s"$prefixSegment/views/{org}/{project}/{id}/projections/{projectionId}/sparql") {
                        concat(
                          (get & parameter("query".as[SparqlQuery])) { query =>
                            emit(blazegraphQuery.query(id, projectionId, ref, query))
                          },
                          (post & entity(as[SparqlQuery])) { query =>
                            emit(blazegraphQuery.query(id, projectionId, ref, query))
                          }
                        )
                      }
                    },
                    // Query all composite views' elasticsearch projections indices
                    (pathPrefix("_") & pathPrefix("_search") & pathEndOrSingleSlash & post) {
                      operationName(s"$prefixSegment/views/{org}/{project}/{id}/projections/_/_search") {
                        (extractQueryParams & sortList & entity(as[JsonObject])) { (qp, sort, query) =>
                          emit(elasticSearchQuery.queryProjections(id, ref, query, qp, sort))
                        }
                      }
                    },
                    // Query a composite views' elasticsearch projection index
                    (idSegment & pathPrefix("_search") & pathEndOrSingleSlash & post) { projectionId =>
                      operationName(s"$prefixSegment/views/{org}/{project}/{id}/projections/{projectionId}/_search") {
                        (extractQueryParams & sortList & entity(as[JsonObject])) { (qp, sort, query) =>
                          emit(elasticSearchQuery.query(id, projectionId, ref, query, qp, sort))
                        }
                      }
                    }
                  )
                },
                pathPrefix("sources") {
                  concat(
                    // Fetch all views' sources statistics
                    (get & pathPrefix("_") & pathPrefix("statistics") & pathEndOrSingleSlash) {
                      operationName(s"$prefixSegment/views/{org}/{project}/{id}/sources/_/statistics") {
                        authorizeFor(ref, permissions.read).apply {
                          emit(views.fetch(id, ref).flatMap(fetchStatistics))
                        }
                      }
                    },
                    // Fetch a views' sources statistics
                    (get & idSegment & pathPrefix("statistics") & pathEndOrSingleSlash) { projectionId =>
                      operationName(s"$prefixSegment/views/{org}/{project}/{id}/sources/{sourceId}/statistics") {
                        authorizeFor(ref, permissions.read).apply {
                          emit(views.fetchSource(id, projectionId, ref).flatMap(fetchSourceStatistics))
                        }
                      }
                    }
                  )
                },
                // Query the common blazegraph namespace for the composite view
                (pathPrefix("sparql") & pathEndOrSingleSlash) {
                  operationName(s"$prefixSegment/views/{org}/{project}/{id}/sparql") {
                    concat(
                      (get & parameter("query".as[SparqlQuery])) { query =>
                        emit(blazegraphQuery.query(id, ref, query))
                      },
                      (post & entity(as[SparqlQuery])) { query =>
                        emit(blazegraphQuery.query(id, ref, query))
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

  private def fetchStatistics(viewRes: ViewResource) =
    statistics(viewRes.value.project, CompositeViews.projectionIds(viewRes.value, viewRes.rev))

  private def fetchProjectionStatistics(viewRes: ViewProjectionResource) = {
    val (view, projection) = viewRes.value
    statistics(
      view.project,
      CompositeViews.projectionIds(view, projection, viewRes.rev).map { case (sId, compositeProjectionId) =>
        (sId, projection.id, compositeProjectionId)
      }
    )
  }
  private def fetchSourceStatistics(viewRes: ViewSourceResource) = {
    val (view, source) = viewRes.value
    statistics(
      view.project,
      CompositeViews.projectionIds(view, source, viewRes.rev).map { case (pId, compositeProjectionId) =>
        (source.id, pId, compositeProjectionId)
      }
    )
  }

  private def statistics(project: ProjectRef, compositeProjectionIds: Set[(Iri, Iri, CompositeViewProjectionId)]) =
    UIO
      .traverse(compositeProjectionIds) { case (sId, pId, projection) =>
        progresses.statistics(project, projection).map(stats => ProjectionStatistics(sId, pId, stats))
      }
      .map[SearchResults[ProjectionStatistics]](list => SearchResults(list.size.toLong, list.sorted))

  private def offsets(
      compositeProjectionIds: Set[(Iri, Iri, CompositeViewProjectionId)]
  )(fetchOffset: ProjectionId => UIO[Offset]): UIO[SearchResults[ProjectionOffset]] =
    UIO
      .traverse(compositeProjectionIds) { case (sId, pId, projection) =>
        fetchOffset(projection).map(offset => ProjectionOffset(sId, pId, offset))
      }
      .map[SearchResults[ProjectionOffset]](list => SearchResults(list.size.toLong, list.sorted))

  private def fetch(id: IdSegment, ref: ProjectRef)(implicit caller: Caller) =
    fetchMap(id, ref, identity)

  private def fetchMap[A: JsonLdEncoder](
      id: IdSegment,
      ref: ProjectRef,
      f: ViewResource => A
  )(implicit caller: Caller) =
    authorizeFor(ref, permissions.read).apply {
      fetchResource(
        rev => emit(views.fetchAt(id, ref, rev).map(f).rejectOn[ViewNotFound]),
        tag => emit(views.fetchBy(id, ref, tag).map(f).rejectOn[ViewNotFound]),
        emit(views.fetch(id, ref).map(f).rejectOn[ViewNotFound])
      )
    }

  private def fetchSource(id: IdSegment, ref: ProjectRef, f: ViewResource => Json)(implicit caller: Caller) =
    authorizeFor(ref, permissions.read).apply {
      fetchResource(
        rev => emit(views.fetchAt(id, ref, rev).map(f).rejectOn[ViewNotFound]),
        tag => emit(views.fetchBy(id, ref, tag).map(f).rejectOn[ViewNotFound]),
        emit(views.fetch(id, ref).map(f).rejectOn[ViewNotFound])
      )
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
      progresses: ProgressesStatistics,
      blazegraphQuery: BlazegraphQuery,
      elasticSearchQuery: ElasticSearchQuery
  )(implicit
      baseUri: BaseUri,
      s: Scheduler,
      cr: RemoteContextResolution,
      ordering: JsonKeyOrdering
  ): Route =
    new CompositeViewsRoutes(
      identities,
      acls,
      projects,
      views,
      restartView,
      restartProjections,
      progresses,
      blazegraphQuery,
      elasticSearchQuery
    ).routes
}
