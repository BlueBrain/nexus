package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.routes

import akka.http.scaladsl.model.StatusCodes.Created
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.routes.BlazegraphViewsDirectives
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewRejection._
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.permissions.{read => Read, write => Write}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.{BlazegraphQuery, CompositeViews, ElasticSearchQuery}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.routes.ElasticSearchViewsDirectives
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.query.SparqlQuery
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.circe.CirceUnmarshalling
import ch.epfl.bluebrain.nexus.delta.sdk.directives.{AuthDirectives, DeltaDirectives, DeltaSchemeDirectives}
import ch.epfl.bluebrain.nexus.delta.sdk.fusion.FusionConfig
import ch.epfl.bluebrain.nexus.delta.sdk.identities.Identities
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.RdfMarshalling
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.model.routes.Tag
import io.circe.{Json, JsonObject}
import monix.execution.Scheduler

/**
  * Composite views routes.
  */
class CompositeViewsRoutes(
    identities: Identities,
    aclCheck: AclCheck,
    views: CompositeViews,
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

  import schemeDirectives._

  def routes: Route =
    pathPrefix("views") {
      extractCaller { implicit caller =>
        resolveProjectRef.apply { implicit ref =>
          concat(
            //Create a view without id segment
            (post & entity(as[Json]) & noParameter("rev") & pathEndOrSingleSlash) { source =>
              authorizeFor(ref, Write).apply {
                emit(
                  Created,
                  views.create(ref, source).mapValue(_.metadata).rejectWhen(decodingFailedOrViewNotFound)
                )
              }
            },
            idSegment { id =>
              concat(
                pathEndOrSingleSlash {
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
                },
                // Fetch a view original source
                (pathPrefix("source") & get & pathEndOrSingleSlash & idSegmentRef(id)) { id =>
                  authorizeFor(ref, Read).apply {
                    emit(views.fetch(id, ref).map(_.value.source).rejectOn[ViewNotFound])
                  }
                },
                pathPrefix("projections") {
                  concat(
                    // Query all composite views' sparql projections namespaces
                    (pathPrefix("_") & pathPrefix("sparql") & pathEndOrSingleSlash) {
                      ((get & parameter("query".as[SparqlQuery])) | (post & entity(as[SparqlQuery]))) { query =>
                        queryResponseType.apply { responseType =>
                          emit(blazegraphQuery.queryProjections(id, ref, query, responseType))
                        }
                      }
                    },
                    // Query a composite views' sparql projection namespace
                    (idSegment & pathPrefix("sparql") & pathEndOrSingleSlash) { projectionId =>
                      ((get & parameter("query".as[SparqlQuery])) | (post & entity(as[SparqlQuery]))) { query =>
                        queryResponseType.apply { responseType =>
                          emit(blazegraphQuery.query(id, projectionId, ref, query, responseType))
                        }
                      }
                    },
                    // Query all composite views' elasticsearch projections indices
                    (pathPrefix("_") & pathPrefix("_search") & pathEndOrSingleSlash & post) {
                      (extractQueryParams & entity(as[JsonObject])) { (qp, query) =>
                        emit(elasticSearchQuery.queryProjections(id, ref, query, qp))
                      }
                    },
                    // Query a composite views' elasticsearch projection index
                    (idSegment & pathPrefix("_search") & pathEndOrSingleSlash & post) { projectionId =>
                      (extractQueryParams & entity(as[JsonObject])) { (qp, query) =>
                        emit(elasticSearchQuery.query(id, projectionId, ref, query, qp))
                      }
                    }
                  )
                },
                // Query the common blazegraph namespace for the composite view
                (pathPrefix("sparql") & pathEndOrSingleSlash) {
                  concat(
                    ((get & parameter("query".as[SparqlQuery])) | (post & entity(as[SparqlQuery]))) { query =>
                      queryResponseType.apply { responseType =>
                        emit(blazegraphQuery.query(id, ref, query, responseType).rejectOn[ViewNotFound])
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

object CompositeViewsRoutes {

  /**
    * @return
    *   the [[Route]] for composite views.
    */
  def apply(
      identities: Identities,
      aclCheck: AclCheck,
      views: CompositeViews,
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
      blazegraphQuery,
      elasticSearchQuery,
      schemeDirectives
    ).routes
}
