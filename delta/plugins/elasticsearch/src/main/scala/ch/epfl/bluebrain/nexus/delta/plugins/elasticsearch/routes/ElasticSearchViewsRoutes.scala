package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.routes

import akka.http.scaladsl.model.StatusCodes.Created
import akka.http.scaladsl.server._
import cats.effect.unsafe.IORuntime
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewRejection._
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model._
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.permissions.{read => Read, write => Write}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.{ElasticSearchViews, ElasticSearchViewsQuery}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaDirectives._
import ch.epfl.bluebrain.nexus.delta.sdk.circe.CirceUnmarshalling
import ch.epfl.bluebrain.nexus.delta.sdk.directives.{AuthDirectives, DeltaSchemeDirectives}
import ch.epfl.bluebrain.nexus.delta.sdk.fusion.FusionConfig
import ch.epfl.bluebrain.nexus.delta.sdk.identities.Identities
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.RdfMarshalling
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.model.routes.Tag
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
  * @param schemeDirectives
  *   directives related to orgs and projects
  * @param index
  *   the indexing action on write operations
  */
final class ElasticSearchViewsRoutes(
    identities: Identities,
    aclCheck: AclCheck,
    views: ElasticSearchViews,
    viewsQuery: ElasticSearchViewsQuery,
    schemeDirectives: DeltaSchemeDirectives,
    index: IndexingAction.Execute[ElasticSearchView]
)(implicit
    baseUri: BaseUri,
    cr: RemoteContextResolution,
    ordering: JsonKeyOrdering,
    fusionConfig: FusionConfig,
    runtime: IORuntime
) extends AuthDirectives(identities, aclCheck)
    with CirceUnmarshalling
    with ElasticSearchViewsDirectives
    with RdfMarshalling {

  import schemeDirectives._

  def routes: Route =
    pathPrefix("views") {
      extractCaller { implicit caller =>
        resolveProjectRef.apply { ref =>
          concat(
            pathEndOrSingleSlash {
              // Create an elasticsearch view without id segment
              (post & pathEndOrSingleSlash & noParameter("rev") & entity(as[Json]) & indexingMode) { (source, mode) =>
                authorizeFor(ref, Write).apply {
                  emit(
                    Created,
                    views
                      .create(ref, source)
                      .flatTap(index(ref, _, mode))
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
                      authorizeFor(ref, Write).apply {
                        (parameter("rev".as[Int].?) & pathEndOrSingleSlash & entity(as[Json])) {
                          case (None, source)      =>
                            // Create an elasticsearch view with id segment
                            emit(
                              Created,
                              views
                                .create(id, ref, source)
                                .flatTap(index(ref, _, mode))
                                .mapValue(_.metadata)
                                .attemptNarrow[ElasticSearchViewRejection]
                                .rejectWhen(decodingFailedOrViewNotFound)
                            )
                          case (Some(rev), source) =>
                            // Update a view
                            emit(
                              views
                                .update(id, ref, rev, source)
                                .flatTap(index(ref, _, mode))
                                .mapValue(_.metadata)
                                .attemptNarrow[ElasticSearchViewRejection]
                                .rejectWhen(decodingFailedOrViewNotFound)
                            )
                        }
                      }
                    },
                    // Deprecate an elasticsearch view
                    (delete & parameter("rev".as[Int])) { rev =>
                      authorizeFor(ref, Write).apply {
                        emit(
                          views
                            .deprecate(id, ref, rev)
                            .flatTap(index(ref, _, mode))
                            .mapValue(_.metadata)
                            .attemptNarrow[ElasticSearchViewRejection]
                            .rejectWhen(decodingFailedOrViewNotFound)
                        )
                      }
                    },
                    // Fetch an elasticsearch view
                    (get & idSegmentRef(id)) { id =>
                      emitOrFusionRedirect(
                        ref,
                        id,
                        authorizeFor(ref, Read).apply {
                          emit(views.fetch(id, ref).attemptNarrow[ElasticSearchViewRejection].rejectOn[ViewNotFound])
                        }
                      )
                    }
                  )
                },
                // Query an elasticsearch view
                (pathPrefix("_search") & post & pathEndOrSingleSlash) {
                  (extractQueryParams & entity(as[JsonObject])) { (qp, query) =>
                    emit(viewsQuery.query(id, ref, query, qp).attemptNarrow[ElasticSearchViewRejection])
                  }
                },
                // Fetch an elasticsearch view original source
                (pathPrefix("source") & get & pathEndOrSingleSlash & idSegmentRef(id)) { id =>
                  authorizeFor(ref, Read).apply {
                    emit(
                      views
                        .fetch(id, ref)
                        .map(_.value.source)
                        .attemptNarrow[ElasticSearchViewRejection]
                        .rejectOn[ViewNotFound]
                    )
                  }
                },
                (pathPrefix("tags") & pathEndOrSingleSlash) {
                  concat(
                    // Fetch an elasticsearch view tags
                    (get & idSegmentRef(id) & authorizeFor(ref, Read)) { id =>
                      emit(
                        views
                          .fetch(id, ref)
                          .map(_.value.tags)
                          .attemptNarrow[ElasticSearchViewRejection]
                          .rejectOn[ViewNotFound]
                      )
                    },
                    // Tag an elasticsearch view
                    (post & parameter("rev".as[Int])) { rev =>
                      authorizeFor(ref, Write).apply {
                        entity(as[Tag]) { case Tag(tagRev, tag) =>
                          emit(
                            Created,
                            views
                              .tag(id, ref, tag, tagRev, rev)
                              .flatTap(index(ref, _, mode))
                              .mapValue(_.metadata)
                              .attemptNarrow[ElasticSearchViewRejection]
                              .rejectWhen(decodingFailedOrViewNotFound)
                          )
                        }
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
      schemeDirectives: DeltaSchemeDirectives,
      index: IndexingAction.Execute[ElasticSearchView]
  )(implicit
      baseUri: BaseUri,
      cr: RemoteContextResolution,
      ordering: JsonKeyOrdering,
      runtime: IORuntime,
      fusionConfig: FusionConfig
  ): Route =
    new ElasticSearchViewsRoutes(
      identities,
      aclCheck,
      views,
      viewsQuery,
      schemeDirectives,
      index
    ).routes
}
