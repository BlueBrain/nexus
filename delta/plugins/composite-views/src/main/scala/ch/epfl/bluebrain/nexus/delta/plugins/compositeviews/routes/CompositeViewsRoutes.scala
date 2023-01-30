package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.routes

import akka.http.scaladsl.model.StatusCodes.Created
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.routes.BlazegraphViewsDirectives
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewRejection._
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model._
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.permissions.{read => Read, write => Write}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.projections.{CompositeIndexingDetails, CompositeProjections}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.{BlazegraphQuery, CompositeViews, ElasticSearchQuery}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.routes.ElasticSearchViewsDirectives
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.rdf.query.SparqlQuery
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.circe.CirceUnmarshalling
import ch.epfl.bluebrain.nexus.delta.sdk.directives.{AuthDirectives, DeltaDirectives, DeltaSchemeDirectives}
import ch.epfl.bluebrain.nexus.delta.sdk.fusion.FusionConfig
import ch.epfl.bluebrain.nexus.delta.sdk.identities.Identities
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.RdfMarshalling
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, IdSegment}
import ch.epfl.bluebrain.nexus.delta.sdk.model.routes.Tag
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults.searchResultsJsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import io.circe.{Json, JsonObject}
import kamon.instrumentation.akka.http.TracingDirectives.operationName
import monix.execution.Scheduler

/**
  * Composite views routes.
  */
class CompositeViewsRoutes(
    identities: Identities,
    aclCheck: AclCheck,
    views: CompositeViews,
    details: CompositeIndexingDetails,
    projections: CompositeProjections,
    blazegraphQuery: BlazegraphQuery,
    elasticSearchQuery: ElasticSearchQuery,
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
                                views.tag(id, ref, tag, tagRev, rev).mapValue(_.metadata).rejectOn[ViewNotFound]
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
                          emit(fetchOffsets(ref, id).rejectWhen(decodingFailedOrViewNotFound))
                        },
                        // Remove all composite view offsets (restart the view)
                        (delete & authorizeFor(ref, Write)) {
                          emit(fullRestart(ref, id).rejectWhen(decodingFailedOrViewNotFound))
                        }
                      )
                    }
                  },
                  // Fetch composite view statistics
                  (get & pathPrefix("statistics") & pathEndOrSingleSlash) {
                    operationName(s"$prefixSegment/views/{org}/{project}/{id}/statistics") {
                      authorizeFor(ref, Read).apply {
                        emit(views.fetch(id, ref).flatMap(details.statistics))
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
                              emit(views.fetch(id, ref).flatMap { v => details.offsets(ref, v.id, v.rev) })
                            },
                            // Remove all composite view projection offsets
                            (delete & authorizeFor(ref, Write)) { emit(fullRebuild(ref, id)) }
                          )
                        }
                      },
                      // Fetch all views' projections statistics
                      (get & pathPrefix("_") & pathPrefix("statistics") & pathEndOrSingleSlash) {
                        operationName(s"$prefixSegment/views/{org}/{project}/{id}/projections/_/statistics") {
                          authorizeFor(ref, Read).apply {
                            emit(
                              views.fetch(id, ref).flatMap { v => details.statistics(v) }
                            )
                          }
                        }
                      },
                      // Manage a views' projection offset
                      (idSegment & pathPrefix("offset") & pathEndOrSingleSlash) { projectionId =>
                        operationName(s"$prefixSegment/views/{org}/{project}/{id}/projections/{projectionId}/offset") {
                          concat(
                            // Fetch a composite view projection offset
                            (get & authorizeFor(ref, Read)) {
                              emit(views.fetchProjection(id, projectionId, ref).flatMap { v =>
                                details.projectionOffsets(ref, v.id, v.rev, v.value._2.id)
                              })
                            },
                            // Remove a composite view projection offset
                            (delete & authorizeFor(ref, Write)) {
                              emit(partialRebuild(ref, id, projectionId))
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
                            emit(
                              views.fetchProjection(id, projectionId, ref).flatMap { v =>
                                details.projectionStatistics(v.map(_._1), v.value._2.id)
                              }
                            )
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
                            emit(views.fetch(id, ref).flatMap { details.statistics })
                          }
                        }
                      },
                      // Fetch a views' sources statistics
                      (get & idSegment & pathPrefix("statistics") & pathEndOrSingleSlash) { projectionId =>
                        operationName(s"$prefixSegment/views/{org}/{project}/{id}/sources/{sourceId}/statistics") {
                          authorizeFor(ref, Read).apply {
                            emit(views.fetchSource(id, projectionId, ref).flatMap { v =>
                              details.sourceStatistics(v.map(_._1), v.value._2.id)
                            })
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

  private def fetchOffsets(project: ProjectRef, id: IdSegment) =
    views.fetch(id, project).flatMap { v => details.offsets(project, v.id, v.rev) }

  private def fullRestart(project: ProjectRef, id: IdSegment)(implicit s: Subject) =
    for {
      v <- views.fetch(id, project)
      o <- details.offsets(project, v.id, v.rev)
      _ <- projections.fullRestart(project, v.id)
    } yield o.map(_.copy(offset = Offset.Start))

  private def fullRebuild(project: ProjectRef, id: IdSegment)(implicit s: Subject) =
    for {
      v <- views.fetch(id, project)
      o <- details.offsets(project, v.id, v.rev)
      _ <- projections.fullRebuild(project, v.id)
    } yield o.map(_.copy(offset = Offset.Start))

  private def partialRebuild(project: ProjectRef, id: IdSegment, projectionId: IdSegment)(implicit s: Subject) =
    for {
      res   <- views.fetchProjection(id, projectionId, project)
      (v, p) = res.value
      o     <- details.projectionOffsets(project, v.id, res.rev, p.id)
      _     <- projections.partialRebuild(project, v.id, p.id)
    } yield o.map(_.copy(offset = Offset.Start))

  private val decodingFailedOrViewNotFound: PartialFunction[CompositeViewRejection, Boolean] = {
    case _: DecodingFailed | _: ViewNotFound | _: InvalidJsonLdFormat => true
  }

}

object CompositeViewsRoutes {

  /**
    * @return
    *   the [[Route]] for composite views.
    */
  def apply(
      identities: Identities,
      aclCheck: AclCheck,
      views: CompositeViews,
      statistics: CompositeIndexingDetails,
      projections: CompositeProjections,
      blazegraphQuery: BlazegraphQuery,
      elasticSearchQuery: ElasticSearchQuery,
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
      statistics,
      projections,
      blazegraphQuery,
      elasticSearchQuery,
      schemeDirectives
    ).routes
}
