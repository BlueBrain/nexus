package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.routes

import akka.http.scaladsl.model.StatusCode
import akka.http.scaladsl.model.StatusCodes.{Created, OK}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import cats.effect.IO
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.SparqlQueryResponse
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.routes.BlazegraphViewsDirectives
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewRejection._
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.{CompositeViewRejection, ViewResource}
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

  private def emitMetadata(statusCode: StatusCode, io: IO[ViewResource]): Route =
    emit(
      statusCode,
      io.mapValue(_.metadata).attemptNarrow[CompositeViewRejection].rejectWhen(decodingFailedOrViewNotFound)
    )

  private def emitMetadata(io: IO[ViewResource]): Route = emitMetadata(OK, io)

  private def emitFetch(io: IO[ViewResource]) =
    emit(io.attemptNarrow[CompositeViewRejection].rejectOn[ViewNotFound])

  private def emitTags(io: IO[ViewResource]) =
    emit(io.map(_.value.tags).attemptNarrow[CompositeViewRejection].rejectOn[ViewNotFound])

  private def emitSource(io: IO[ViewResource]) =
    emit(io.map(_.value.source).attemptNarrow[CompositeViewRejection].rejectOn[ViewNotFound])

  private def emitSparqlResponse[R <: SparqlQueryResponse](io: IO[R]) =
    emit(io.attemptNarrow[CompositeViewRejection].rejectOn[ViewNotFound])

  private def emitElasticsearchResponse(io: IO[Json]) =
    emit(io.attemptNarrow[CompositeViewRejection].rejectOn[ViewNotFound])

  def routes: Route =
    pathPrefix("views") {
      extractCaller { implicit caller =>
        resolveProjectRef.apply { implicit ref =>
          concat(
            //Create a view without id segment
            (post & entity(as[Json]) & noParameter("rev") & pathEndOrSingleSlash) { source =>
              authorizeFor(ref, Write).apply {
                emitMetadata(Created, views.create(ref, source))
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
                            emitMetadata(Created, views.create(id, ref, source))
                          case (Some(rev), source) =>
                            // Update a view
                            emitMetadata(views.update(id, ref, rev, source))
                        }
                      }
                    },
                    //Deprecate a view
                    (delete & parameter("rev".as[Int])) { rev =>
                      authorizeFor(ref, Write).apply {
                        emitMetadata(views.deprecate(id, ref, rev))
                      }
                    },
                    // Fetch a view
                    (get & idSegmentRef(id)) { id =>
                      emitOrFusionRedirect(
                        ref,
                        id,
                        authorizeFor(ref, Read).apply {
                          emitFetch(views.fetch(id, ref))
                        }
                      )
                    }
                  )
                },
                (pathPrefix("tags") & pathEndOrSingleSlash) {
                  concat(
                    // Fetch tags for a view
                    (get & idSegmentRef(id) & authorizeFor(ref, Read)) { id =>
                      emitTags(views.fetch(id, ref))
                    },
                    // Tag a view
                    (post & parameter("rev".as[Int])) { rev =>
                      authorizeFor(ref, Write).apply {
                        entity(as[Tag]) { case Tag(tagRev, tag) =>
                          emitMetadata(Created, views.tag(id, ref, tag, tagRev, rev))
                        }
                      }
                    }
                  )
                },
                // Fetch a view original source
                (pathPrefix("source") & get & pathEndOrSingleSlash & idSegmentRef(id)) { id =>
                  authorizeFor(ref, Read).apply {
                    emitSource(views.fetch(id, ref))
                  }
                },
                pathPrefix("projections") {
                  concat(
                    // Query all composite views' sparql projections namespaces
                    (pathPrefix("_") & pathPrefix("sparql") & pathEndOrSingleSlash) {
                      ((get & parameter("query".as[SparqlQuery])) | (post & entity(as[SparqlQuery]))) { query =>
                        queryResponseType.apply { responseType =>
                          emitSparqlResponse(blazegraphQuery.queryProjections(id, ref, query, responseType))
                        }
                      }
                    },
                    // Query a composite views' sparql projection namespace
                    (idSegment & pathPrefix("sparql") & pathEndOrSingleSlash) { projectionId =>
                      ((get & parameter("query".as[SparqlQuery])) | (post & entity(as[SparqlQuery]))) { query =>
                        queryResponseType.apply { responseType =>
                          emitSparqlResponse(blazegraphQuery.query(id, projectionId, ref, query, responseType))
                        }
                      }
                    },
                    // Query all composite views' elasticsearch projections indices
                    (pathPrefix("_") & pathPrefix("_search") & pathEndOrSingleSlash & post) {
                      (extractQueryParams & entity(as[JsonObject])) { (qp, query) =>
                        emitElasticsearchResponse(elasticSearchQuery.queryProjections(id, ref, query, qp))
                      }
                    },
                    // Query a composite views' elasticsearch projection index
                    (idSegment & pathPrefix("_search") & pathEndOrSingleSlash & post) { projectionId =>
                      (extractQueryParams & entity(as[JsonObject])) { (qp, query) =>
                        emitElasticsearchResponse(elasticSearchQuery.query(id, projectionId, ref, query, qp))
                      }
                    }
                  )
                },
                // Query the common blazegraph namespace for the composite view
                (pathPrefix("sparql") & pathEndOrSingleSlash) {
                  concat(
                    ((get & parameter("query".as[SparqlQuery])) | (post & entity(as[SparqlQuery]))) { query =>
                      queryResponseType.apply { responseType =>
                        emitSparqlResponse(blazegraphQuery.query(id, ref, query, responseType))
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
