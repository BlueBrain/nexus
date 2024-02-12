package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.routes

import akka.http.scaladsl.model.StatusCodes.Created
import akka.http.scaladsl.server._
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewRejection._
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model._
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.permissions.{read => Read, write => Write}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.{ElasticSearchViews, ElasticSearchViewsQuery}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.circe.CirceUnmarshalling
import ch.epfl.bluebrain.nexus.delta.sdk.directives.AuthDirectives
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaDirectives._
import ch.epfl.bluebrain.nexus.delta.sdk.fusion.FusionConfig
import ch.epfl.bluebrain.nexus.delta.sdk.identities.Identities
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.RdfMarshalling
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import io.circe.{Json, JsonObject}

/**
  * The elasticsearch views routes
  *
  * @param identities
  *   the identity module
  * @param aclCheck
  *   to check acls
  * @param views
  *   the elasticsearch views operations bundle
  * @param viewsQuery
  *   the elasticsearch views query operations bundle
  * @param index
  *   the indexing action on write operations
  */
final class ElasticSearchViewsRoutes(
    identities: Identities,
    aclCheck: AclCheck,
    views: ElasticSearchViews,
    viewsQuery: ElasticSearchViewsQuery,
    index: IndexingAction.Execute[ElasticSearchView]
)(implicit
    baseUri: BaseUri,
    cr: RemoteContextResolution,
    ordering: JsonKeyOrdering,
    fusionConfig: FusionConfig
) extends AuthDirectives(identities, aclCheck)
    with CirceUnmarshalling
    with ElasticSearchViewsDirectives
    with RdfMarshalling {

  def routes: Route =
    pathPrefix("views") {
      extractCaller { implicit caller =>
        projectRef { project =>
          concat(
            pathEndOrSingleSlash {
              // Create an elasticsearch view without id segment
              (post & pathEndOrSingleSlash & noParameter("rev") & entity(as[Json]) & indexingMode) { (source, mode) =>
                authorizeFor(project, Write).apply {
                  emit(
                    Created,
                    views
                      .create(project, source)
                      .flatTap(index(project, _, mode))
                      .mapValue(_.metadata)
                      .attemptNarrow[ElasticSearchViewRejection]
                      .rejectWhen(decodingFailedOrViewNotFound)
                  )
                }
              }
            },
            (idSegment & indexingMode) { (id, mode) =>
              concat(
                pathEndOrSingleSlash {
                  concat(
                    // Create or update an elasticsearch view
                    put {
                      authorizeFor(project, Write).apply {
                        (parameter("rev".as[Int].?) & pathEndOrSingleSlash & entity(as[Json])) {
                          case (None, source)      =>
                            // Create an elasticsearch view with id segment
                            emit(
                              Created,
                              views
                                .create(id, project, source)
                                .flatTap(index(project, _, mode))
                                .mapValue(_.metadata)
                                .attemptNarrow[ElasticSearchViewRejection]
                                .rejectWhen(decodingFailedOrViewNotFound)
                            )
                          case (Some(rev), source) =>
                            // Update a view
                            emit(
                              views
                                .update(id, project, rev, source)
                                .flatTap(index(project, _, mode))
                                .mapValue(_.metadata)
                                .attemptNarrow[ElasticSearchViewRejection]
                                .rejectWhen(decodingFailedOrViewNotFound)
                            )
                        }
                      }
                    },
                    // Deprecate an elasticsearch view
                    (delete & parameter("rev".as[Int])) { rev =>
                      authorizeFor(project, Write).apply {
                        emit(
                          views
                            .deprecate(id, project, rev)
                            .flatTap(index(project, _, mode))
                            .mapValue(_.metadata)
                            .attemptNarrow[ElasticSearchViewRejection]
                            .rejectWhen(decodingFailedOrViewNotFound)
                        )
                      }
                    },
                    // Fetch an elasticsearch view
                    (get & idSegmentRef(id)) { id =>
                      emitOrFusionRedirect(
                        project,
                        id,
                        authorizeFor(project, Read).apply {
                          emit(
                            views.fetch(id, project).attemptNarrow[ElasticSearchViewRejection].rejectOn[ViewNotFound]
                          )
                        }
                      )
                    }
                  )
                },
                // Undeprecate an elasticsearch view
                (pathPrefix("undeprecate") & put & pathEndOrSingleSlash & parameter("rev".as[Int])) { rev =>
                  authorizeFor(project, Write).apply {
                    emit(
                      views
                        .undeprecate(id, project, rev)
                        .flatTap(index(project, _, mode))
                        .mapValue(_.metadata)
                        .attemptNarrow[ElasticSearchViewRejection]
                        .rejectWhen(decodingFailedOrViewNotFound)
                    )
                  }
                },
                // Query an elasticsearch view
                (pathPrefix("_search") & post & pathEndOrSingleSlash) {
                  (extractQueryParams & entity(as[JsonObject])) { (qp, query) =>
                    emit(viewsQuery.query(id, project, query, qp).attemptNarrow[ElasticSearchViewRejection])
                  }
                },
                // Fetch an elasticsearch view original source
                (pathPrefix("source") & get & pathEndOrSingleSlash & idSegmentRef(id)) { id =>
                  authorizeFor(project, Read).apply {
                    emit(
                      views
                        .fetch(id, project)
                        .map(_.value.source)
                        .attemptNarrow[ElasticSearchViewRejection]
                        .rejectOn[ViewNotFound]
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

object ElasticSearchViewsRoutes {

  /**
    * @return
    *   the [[Route]] for elasticsearch views
    */
  def apply(
      identities: Identities,
      aclCheck: AclCheck,
      views: ElasticSearchViews,
      viewsQuery: ElasticSearchViewsQuery,
      index: IndexingAction.Execute[ElasticSearchView]
  )(implicit
      baseUri: BaseUri,
      cr: RemoteContextResolution,
      ordering: JsonKeyOrdering,
      fusionConfig: FusionConfig
  ): Route =
    new ElasticSearchViewsRoutes(
      identities,
      aclCheck,
      views,
      viewsQuery,
      index
    ).routes
}
