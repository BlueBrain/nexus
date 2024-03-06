package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.routes

import akka.http.scaladsl.model.StatusCode
import akka.http.scaladsl.model.StatusCodes.{Created, OK}
import akka.http.scaladsl.server.Route
import cats.effect.IO
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.SparqlQueryResponse
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.routes.BlazegraphViewsDirectives
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewRejection._
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.permissions.{read => Read, write => Write}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.{CompositeViewRejection, ViewResource}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.{BlazegraphQuery, CompositeViews, ElasticSearchQuery}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.routes.ElasticSearchViewsDirectives
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.query.SparqlQuery
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.circe.CirceUnmarshalling
import ch.epfl.bluebrain.nexus.delta.sdk.directives.{AuthDirectives, DeltaDirectives}
import ch.epfl.bluebrain.nexus.delta.sdk.fusion.FusionConfig
import ch.epfl.bluebrain.nexus.delta.sdk.identities.Identities
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.RdfMarshalling
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import io.circe.{Json, JsonObject}

/**
  * Composite views routes.
  */
class CompositeViewsRoutes(
    identities: Identities,
    aclCheck: AclCheck,
    views: CompositeViews,
    blazegraphQuery: BlazegraphQuery,
    elasticSearchQuery: ElasticSearchQuery
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

  private val rejectPredicateOnWrite: PartialFunction[CompositeViewRejection, Boolean] = {
    case _: ViewNotFound | _: CompositeVieDecodingRejection => true
  }

  private def emitMetadata(statusCode: StatusCode, io: IO[ViewResource]): Route =
    emit(
      statusCode,
      io.mapValue(_.metadata).attemptNarrow[CompositeViewRejection].rejectWhen(rejectPredicateOnWrite)
    )

  private def emitMetadata(io: IO[ViewResource]): Route = emitMetadata(OK, io)

  private def emitFetch(io: IO[ViewResource]) =
    emit(io.attemptNarrow[CompositeViewRejection].rejectOn[ViewNotFound])

  private def emitSource(io: IO[ViewResource]) =
    emit(io.map(_.value.source).attemptNarrow[CompositeViewRejection].rejectOn[ViewNotFound])

  private def emitSparqlResponse[R <: SparqlQueryResponse](io: IO[R]) =
    emit(io.attemptNarrow[CompositeViewRejection].rejectOn[ViewNotFound])

  private def emitElasticsearchResponse(io: IO[Json]) =
    emit(io.attemptNarrow[CompositeViewRejection].rejectOn[ViewNotFound])

  def routes: Route =
    pathPrefix("views") {
      extractCaller { implicit caller =>
        projectRef { implicit project =>
          concat(
            //Create a view without id segment
            (pathEndOrSingleSlash & post & entity(as[Json]) & noParameter("rev")) { source =>
              authorizeFor(project, Write).apply {
                emitMetadata(Created, views.create(project, source))
              }
            },
            idSegment { viewId =>
              concat(
                pathEndOrSingleSlash {
                  concat(
                    put {
                      authorizeFor(project, Write).apply {
                        (parameter("rev".as[Int].?) & pathEndOrSingleSlash & entity(as[Json])) {
                          case (None, source)      =>
                            // Create a view with id segment
                            emitMetadata(Created, views.create(viewId, project, source))
                          case (Some(rev), source) =>
                            // Update a view
                            emitMetadata(views.update(viewId, project, rev, source))
                        }
                      }
                    },
                    //Deprecate a view
                    (delete & parameter("rev".as[Int])) { rev =>
                      authorizeFor(project, Write).apply {
                        emitMetadata(views.deprecate(viewId, project, rev))
                      }
                    },
                    // Fetch a view
                    (get & idSegmentRef(viewId)) { id =>
                      emitOrFusionRedirect(
                        project,
                        id,
                        authorizeFor(project, Read).apply {
                          emitFetch(views.fetch(id, project))
                        }
                      )
                    }
                  )
                },
                // Undeprecate a view
                (pathPrefix("undeprecate") & put & pathEndOrSingleSlash & parameter("rev".as[Int])) { rev =>
                  authorizeFor(project, Write).apply {
                    emitMetadata(views.undeprecate(viewId, project, rev))
                  }
                },
                // Fetch a view original source
                (pathPrefix("source") & get & pathEndOrSingleSlash & idSegmentRef(viewId)) { id =>
                  authorizeFor(project, Read).apply {
                    emitSource(views.fetch(id, project))
                  }
                },
                pathPrefix("projections") {
                  concat(
                    // Query all composite views' sparql projections namespaces
                    (pathPrefix("_") & pathPrefix("sparql") & pathEndOrSingleSlash) {
                      ((get & parameter("query".as[SparqlQuery])) | (post & entity(as[SparqlQuery]))) { query =>
                        queryResponseType.apply { responseType =>
                          emitSparqlResponse(blazegraphQuery.queryProjections(viewId, project, query, responseType))
                        }
                      }
                    },
                    // Query a composite views' sparql projection namespace
                    (idSegment & pathPrefix("sparql") & pathEndOrSingleSlash) { projectionId =>
                      ((get & parameter("query".as[SparqlQuery])) | (post & entity(as[SparqlQuery]))) { query =>
                        queryResponseType.apply { responseType =>
                          emitSparqlResponse(blazegraphQuery.query(viewId, projectionId, project, query, responseType))
                        }
                      }
                    },
                    // Query all composite views' elasticsearch projections indices
                    (pathPrefix("_") & pathPrefix("_search") & pathEndOrSingleSlash & post) {
                      (extractQueryParams & entity(as[JsonObject])) { (qp, query) =>
                        emitElasticsearchResponse(elasticSearchQuery.queryProjections(viewId, project, query, qp))
                      }
                    },
                    // Query a composite views' elasticsearch projection index
                    (idSegment & pathPrefix("_search") & pathEndOrSingleSlash & post) { projectionId =>
                      (extractQueryParams & entity(as[JsonObject])) { (qp, query) =>
                        emitElasticsearchResponse(elasticSearchQuery.query(viewId, projectionId, project, query, qp))
                      }
                    }
                  )
                },
                // Query the common blazegraph namespace for the composite view
                (pathPrefix("sparql") & pathEndOrSingleSlash) {
                  concat(
                    ((get & parameter("query".as[SparqlQuery])) | (post & entity(as[SparqlQuery]))) { query =>
                      queryResponseType.apply { responseType =>
                        emitSparqlResponse(blazegraphQuery.query(viewId, project, query, responseType))
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
      elasticSearchQuery: ElasticSearchQuery
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
      elasticSearchQuery
    ).routes
}
