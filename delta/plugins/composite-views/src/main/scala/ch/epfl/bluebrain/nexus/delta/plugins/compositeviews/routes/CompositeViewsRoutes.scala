package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.routes

import akka.http.scaladsl.model.StatusCodes.Created
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.routes.BlazegraphViewsDirectives
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.client.DeltaClient
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewRejection._
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewSource.{CrossProjectSource, ProjectSource, RemoteProjectSource}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model._
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.permissions.{read => Read, write => Write}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.routes.CompositeViewsRoutes.{RestartProjections, RestartView}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.{BlazegraphQuery, CompositeViews, ElasticSearchQuery}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.routes.ElasticSearchViewsDirectives
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.rdf.query.SparqlQuery
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.ProgressesStatistics
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.circe.CirceUnmarshalling
import ch.epfl.bluebrain.nexus.delta.sdk.directives.{AuthDirectives, DeltaDirectives, DeltaSchemeDirectives}
import ch.epfl.bluebrain.nexus.delta.sdk.fusion.FusionConfig
import ch.epfl.bluebrain.nexus.delta.sdk.identities.Identities
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.RdfMarshalling
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.model.routes.Tag
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults.searchResultsJsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.ProjectionId
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.ProjectionId.CompositeViewProjectionId
import io.circe.{Json, JsonObject}
import kamon.instrumentation.akka.http.TracingDirectives.operationName
import monix.bio.{IO, UIO}
import monix.execution.Scheduler

/**
  * Composite views routes.
  */
class CompositeViewsRoutes(
    identities: Identities,
    aclCheck: AclCheck,
    views: CompositeViews,
    restartView: RestartView,
    restartProjections: RestartProjections,
    progresses: ProgressesStatistics,
    blazegraphQuery: BlazegraphQuery,
    elasticSearchQuery: ElasticSearchQuery,
    deltaClient: DeltaClient,
    schemeDirectives: DeltaSchemeDirectives
)(implicit
    baseUri: BaseUri,
    s: Scheduler,
    cr: RemoteContextResolution,
    ordering: JsonKeyOrdering,
    fusionConfig: FusionConfig
) extends AuthDirectives(identities, aclCheck)
    with DeltaDirectives
    with CirceUnmarshalling
    with RdfMarshalling
    with ElasticSearchViewsDirectives
    with BlazegraphViewsDirectives {

  import baseUri.prefixSegment
  import schemeDirectives._

  implicit private val offsetsSearchJsonLdEncoder: JsonLdEncoder[SearchResults[ProjectionOffset]] =
    searchResultsJsonLdEncoder(ContextValue(contexts.offset))

  implicit private val statisticsSearchJsonLdEncoder: JsonLdEncoder[SearchResults[ProjectionStatistics]] =
    searchResultsJsonLdEncoder(ContextValue(contexts.statistics))

  def routes: Route =
    (baseUriPrefix(baseUri.prefix) & replaceUri("views", schema.iri)) {
      pathPrefix("views") {
        extractCaller { implicit caller =>
          resolveProjectRef.apply { implicit ref =>
            concat(
              //Create a view without id segment
              (post & entity(as[Json]) & noParameter("rev") & pathEndOrSingleSlash & operationName(
                s"$prefixSegment/views/{org}/{project}"
              )) { source =>
                authorizeFor(ref, Write).apply {
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
                        authorizeFor(ref, Write).apply {
                          (parameter("rev".as[Int].?) & pathEndOrSingleSlash & entity(as[Json])) {
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
                      (delete & parameter("rev".as[Int])) { rev =>
                        authorizeFor(ref, Write).apply {
                          emit(views.deprecate(id, ref, rev).mapValue(_.metadata).rejectOn[ViewNotFound])
                        }
                      },
                      // Fetch a view
                      (get & idSegmentRef(id)) { id =>
                        emitOrFusionRedirect(
                          ref,
                          id,
                          authorizeFor(ref, Read).apply {
                            emit(views.fetch(id, ref).rejectOn[ViewNotFound])
                          }
                        )
                      }
                    )
                  },
                  (pathPrefix("tags") & pathEndOrSingleSlash) {
                    operationName(s"$prefixSegment/views/{org}/{project}/{id}/tags") {
                      concat(
                        // Fetch tags for a view
                        (get & idSegmentRef(id) & authorizeFor(ref, Read)) { id =>
                          emit(views.fetch(id, ref).map(_.value.tags).rejectOn[ViewNotFound])
                        },
                        // Tag a view
                        (post & parameter("rev".as[Int])) { rev =>
                          authorizeFor(ref, Write).apply {
                            entity(as[Tag]) { case Tag(tagRev, tag) =>
                              emit(
                                Created,
                                views.tag(id, ref, tag, tagRev.toInt, rev).mapValue(_.metadata).rejectOn[ViewNotFound]
                              )
                            }
                          }
                        }
                      )
                    }
                  },
                  // Fetch a view original source
                  (pathPrefix("source") & get & pathEndOrSingleSlash & idSegmentRef(id)) { id =>
                    operationName(s"$prefixSegment/views/{org}/{project}/{id}/source") {
                      authorizeFor(ref, Read).apply {
                        emit(views.fetch(id, ref).map(_.value.source).rejectOn[ViewNotFound])
                      }
                    }
                  },
                  // Manage composite view offsets
                  (pathPrefix("offset") & pathEndOrSingleSlash) {
                    operationName(s"$prefixSegment/views/{org}/{project}/{id}/offset") {
                      concat(
                        // Fetch all composite view offsets
                        (get & authorizeFor(ref, Read)) {
                          emit(views.fetch(id, ref).flatMap(fetchOffsets).rejectWhen(decodingFailedOrViewNotFound))
                        },
                        // Remove all composite view offsets (restart the view)
                        (delete & authorizeFor(ref, Write)) {
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
                      authorizeFor(ref, Read).apply {
                        emit(views.fetch(id, ref).flatMap(viewStatistics))
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
                            (get & authorizeFor(ref, Read)) {
                              emit(views.fetch(id, ref).flatMap(fetchOffsets))
                            },
                            // Remove all composite view projection offsets
                            (delete & authorizeFor(ref, Write)) {
                              emit(
                                views.fetch(id, ref).flatMap { v =>
                                  val projectionIds = CompositeViews.projectionIds(v.value, v.rev.toInt).map(_._3)
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
                          authorizeFor(ref, Read).apply {
                            emit(views.fetch(id, ref).flatMap(viewStatistics))
                          }
                        }
                      },
                      // Manage a views' projection offset
                      (idSegment & pathPrefix("offset") & pathEndOrSingleSlash) { projectionId =>
                        operationName(s"$prefixSegment/views/{org}/{project}/{id}/projections/{projectionId}/offset") {
                          concat(
                            // Fetch a composite view projection offset
                            (get & authorizeFor(ref, Read)) {
                              emit(views.fetchProjection(id, projectionId, ref).flatMap(fetchProjectionOffsets))
                            },
                            // Remove a composite view projection offset
                            (delete & authorizeFor(ref, Write)) {
                              emit(
                                views
                                  .fetchProjection(id, projectionId, ref)
                                  .flatMap { v =>
                                    val (view, projection) = v.value
                                    val projectionIds      =
                                      CompositeViews.projectionIds(view, projection, v.rev.toInt).map(_._2)
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
                          authorizeFor(ref, Read).apply {
                            emit(views.fetchProjection(id, projectionId, ref).flatMap(projectionStatistics))
                          }
                        }
                      },
                      // Query all composite views' sparql projections namespaces
                      (pathPrefix("_") & pathPrefix("sparql") & pathEndOrSingleSlash) {
                        operationName(s"$prefixSegment/views/{org}/{project}/{id}/projections/_/sparql") {
                          concat(
                            ((get & parameter("query".as[SparqlQuery])) | (post & entity(as[SparqlQuery]))) { query =>
                              queryResponseType.apply { responseType =>
                                emit(blazegraphQuery.queryProjections(id, ref, query, responseType))
                              }
                            }
                          )
                        }
                      },
                      // Query a composite views' sparql projection namespace
                      (idSegment & pathPrefix("sparql") & pathEndOrSingleSlash) { projectionId =>
                        operationName(s"$prefixSegment/views/{org}/{project}/{id}/projections/{projectionId}/sparql") {
                          concat(
                            ((get & parameter("query".as[SparqlQuery])) | (post & entity(as[SparqlQuery]))) { query =>
                              queryResponseType.apply { responseType =>
                                emit(blazegraphQuery.query(id, projectionId, ref, query, responseType))
                              }
                            }
                          )
                        }
                      },
                      // Query all composite views' elasticsearch projections indices
                      (pathPrefix("_") & pathPrefix("_search") & pathEndOrSingleSlash & post) {
                        operationName(s"$prefixSegment/views/{org}/{project}/{id}/projections/_/_search") {
                          (extractQueryParams & entity(as[JsonObject])) { (qp, query) =>
                            emit(elasticSearchQuery.queryProjections(id, ref, query, qp))
                          }
                        }
                      },
                      // Query a composite views' elasticsearch projection index
                      (idSegment & pathPrefix("_search") & pathEndOrSingleSlash & post) { projectionId =>
                        operationName(s"$prefixSegment/views/{org}/{project}/{id}/projections/{projectionId}/_search") {
                          (extractQueryParams & entity(as[JsonObject])) { (qp, query) =>
                            emit(elasticSearchQuery.query(id, projectionId, ref, query, qp))
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
                          authorizeFor(ref, Read).apply {
                            emit(views.fetch(id, ref).flatMap(viewStatistics))
                          }
                        }
                      },
                      // Fetch a views' sources statistics
                      (get & idSegment & pathPrefix("statistics") & pathEndOrSingleSlash) { projectionId =>
                        operationName(s"$prefixSegment/views/{org}/{project}/{id}/sources/{sourceId}/statistics") {
                          authorizeFor(ref, Read).apply {
                            emit(views.fetchSource(id, projectionId, ref).flatMap(sourceStatistics))
                          }
                        }
                      }
                    )
                  },
                  // Query the common blazegraph namespace for the composite view
                  (pathPrefix("sparql") & pathEndOrSingleSlash) {
                    operationName(s"$prefixSegment/views/{org}/{project}/{id}/sparql") {
                      concat(
                        ((get & parameter("query".as[SparqlQuery])) | (post & entity(as[SparqlQuery]))) { query =>
                          queryResponseType.apply { responseType =>
                            emit(blazegraphQuery.query(id, ref, query, responseType).rejectOn[ViewNotFound])
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

  private def fetchOffsets(viewRes: ViewResource) = {
    //TODO: migrate project statistics
    val fetchOffset = (_: ProjectionId) => UIO.pure(Offset.Start)
    offsets(CompositeViews.projectionIds(viewRes.value, viewRes.rev.toInt))(fetchOffset)
  }

  private def resetOffsets(viewRes: ViewResource) =
    offsets(CompositeViews.projectionIds(viewRes.value, viewRes.rev.toInt))(_ => UIO.pure(Offset.Start))

  private def viewStatistics(viewRes: ViewResource) = {
    val entries = for {
      source     <- viewRes.value.sources.toSortedSet
      projection <- viewRes.value.projections.toSortedSet
    } yield (source, projection)
    statisticsFor(viewRes, entries)
  }

  private def projectionStatistics(projRes: ViewProjectionResource) = {
    val (view, projection) = projRes.value
    val viewRes            = projRes.map { case (view, _) => view }
    val entries            = view.sources.map(source => (source, projection))
    statisticsFor(viewRes, entries.toSortedSet)
  }

  private def sourceStatistics(sourceRes: ViewSourceResource) = {
    val (view, source) = sourceRes.value
    val viewRes        = sourceRes.map { case (view, _) => view }
    val entries        = view.projections.map(projection => (source, projection))
    statisticsFor(viewRes, entries.toSortedSet)
  }

  private def statisticsFor(
      viewRes: ViewResource,
      entries: Iterable[(CompositeViewSource, CompositeViewProjection)]
  ): IO[CompositeViewRejection, SearchResults[ProjectionStatistics]] =
    IO.traverse(entries) { case (source, projection) =>
      statisticsFor(viewRes, source, projection)
    }.map(list => SearchResults(list.size.toLong, list.sorted))

  private def statisticsFor(
      viewRes: ViewResource,
      source: CompositeViewSource,
      projection: CompositeViewProjection
  ): IO[CompositeViewRejection, ProjectionStatistics] = {
    val statsIO = source match {
      case source: RemoteProjectSource =>
        deltaClient
          .projectCount(source)
          .flatMap(count =>
            progresses.statistics(count, CompositeViews.projectionId(source, projection, viewRes.rev.toInt))
          )
          .mapError(clientError => InvalidRemoteProjectSource(source, clientError))
      case source: ProjectSource       =>
        progresses.statistics(viewRes.value.project, CompositeViews.projectionId(source, projection, viewRes.rev.toInt))
      case source: CrossProjectSource  =>
        progresses.statistics(source.project, CompositeViews.projectionId(source, projection, viewRes.rev.toInt))
    }
    statsIO.map { stats => ProjectionStatistics(source.id, projection.id, stats) }
  }

  private def fetchProjectionOffsets(viewRes: ViewProjectionResource) = {
    //TODO: migrate project statistics
    val fetchOffset        = (_: ProjectionId) => UIO.pure(Offset.Start)
    val (view, projection) = viewRes.value
    offsets(CompositeViews.projectionIds(view, projection, viewRes.rev.toInt).map { case (sId, compositeProjectionId) =>
      (sId, projection.id, compositeProjectionId)
    })(fetchOffset)
  }

  private def resetProjectionOffsets(viewRes: ViewProjectionResource) = {
    val (view, projection) = viewRes.value
    offsets(CompositeViews.projectionIds(view, projection, viewRes.rev.toInt).map { case (sId, compositeProjectionId) =>
      (sId, projection.id, compositeProjectionId)
    })(_ => UIO.pure(Offset.Start))
  }

  private def offsets(
      compositeProjectionIds: Set[(Iri, Iri, CompositeViewProjectionId)]
  )(fetchOffset: ProjectionId => UIO[Offset]): UIO[SearchResults[ProjectionOffset]] =
    UIO
      .traverse(compositeProjectionIds) { case (sId, pId, projection) =>
        fetchOffset(projection).map(offset => ProjectionOffset(sId, pId, offset))
      }
      .map[SearchResults[ProjectionOffset]](list => SearchResults(list.size.toLong, list.sorted))

  private val decodingFailedOrViewNotFound: PartialFunction[CompositeViewRejection, Boolean] = {
    case _: DecodingFailed | _: ViewNotFound | _: InvalidJsonLdFormat => true
  }

}

object CompositeViewsRoutes {

  type RestartView = (Iri, ProjectRef) => UIO[Unit]

  type RestartProjections = (Iri, ProjectRef, Set[CompositeViewProjectionId]) => UIO[Unit]

  /**
    * @return
    *   the [[Route]] for composite views.
    */
  def apply(
      identities: Identities,
      aclCheck: AclCheck,
      views: CompositeViews,
      restartView: RestartView,
      restartProjections: RestartProjections,
      progresses: ProgressesStatistics,
      blazegraphQuery: BlazegraphQuery,
      elasticSearchQuery: ElasticSearchQuery,
      deltaClient: DeltaClient,
      schemeDirectives: DeltaSchemeDirectives
  )(implicit
      baseUri: BaseUri,
      s: Scheduler,
      cr: RemoteContextResolution,
      ordering: JsonKeyOrdering,
      fusionConfig: FusionConfig
  ): Route =
    new CompositeViewsRoutes(
      identities,
      aclCheck,
      views,
      restartView,
      restartProjections,
      progresses,
      blazegraphQuery,
      elasticSearchQuery,
      deltaClient,
      schemeDirectives
    ).routes
}
