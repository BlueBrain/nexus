package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.routes

import akka.http.scaladsl.model.StatusCodes.Created
import akka.http.scaladsl.model.{StatusCode, StatusCodes}
import akka.http.scaladsl.server._
import cats.effect.IO
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.PointInTime
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
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.JsonLdRejection.{DecodingFailed, InvalidJsonLdFormat}
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.{OriginalSource, RdfMarshalling}
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import io.circe.syntax.EncoderOps
import io.circe.{Json, JsonObject}

import java.util.concurrent.TimeUnit
import scala.concurrent.duration.Duration

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

  private val rejectPredicateOnWrite: PartialFunction[ElasticSearchViewRejection, Boolean] = {
    case _: ViewNotFound | _: ElasticSearchDecodingRejection => true
  }

  private def emitMetadataOrReject(statusCode: StatusCode, io: IO[ViewResource]): Route = {
    emit(
      statusCode,
      io.mapValue(_.metadata)
        .adaptError {
          case d: DecodingFailed      => ElasticSearchDecodingRejection(d)
          case i: InvalidJsonLdFormat => ElasticSearchDecodingRejection(i)
          case other                  => other
        }
        .attemptNarrow[ElasticSearchViewRejection]
        .rejectWhen(rejectPredicateOnWrite)
    )
  }

  private def emitMetadataOrReject(io: IO[ViewResource]): Route = emitMetadataOrReject(StatusCodes.OK, io)

  private def emitFetch(io: IO[ViewResource]): Route =
    emit(io.attemptNarrow[ElasticSearchViewRejection].rejectOn[ViewNotFound])

  private def emitSource(io: IO[ViewResource]): Route =
    emit(
      io.map { resource => OriginalSource(resource, resource.value.source) }
        .attemptNarrow[ElasticSearchViewRejection]
        .rejectOn[ViewNotFound]
    )

  def routes: Route =
    pathPrefix("views") {
      extractCaller { implicit caller =>
        projectRef { project =>
          concat(
            pathEndOrSingleSlash {
              // Create an elasticsearch view without id segment
              (post & pathEndOrSingleSlash & noParameter("rev") & entity(as[Json]) & indexingMode) { (source, mode) =>
                authorizeFor(project, Write).apply {
                  emitMetadataOrReject(
                    Created,
                    views.create(project, source).flatTap(index(project, _, mode))
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
                            emitMetadataOrReject(
                              Created,
                              views.create(id, project, source).flatTap(index(project, _, mode))
                            )
                          case (Some(rev), source) =>
                            // Update a view
                            emitMetadataOrReject(
                              views.update(id, project, rev, source).flatTap(index(project, _, mode))
                            )
                        }
                      }
                    },
                    // Deprecate an elasticsearch view
                    (delete & parameter("rev".as[Int])) { rev =>
                      authorizeFor(project, Write).apply {
                        emitMetadataOrReject(
                          views.deprecate(id, project, rev).flatTap(index(project, _, mode))
                        )
                      }
                    },
                    // Fetch an elasticsearch view
                    (get & idSegmentRef(id)) { id =>
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
                // Undeprecate an elasticsearch view
                (pathPrefix("undeprecate") & put & pathEndOrSingleSlash & parameter("rev".as[Int])) { rev =>
                  authorizeFor(project, Write).apply {
                    emitMetadataOrReject(
                      views.undeprecate(id, project, rev).flatTap(index(project, _, mode))
                    )
                  }
                },
                // Query an elasticsearch view
                (pathPrefix("_search") & post & pathEndOrSingleSlash) {
                  (extractQueryParams & entity(as[JsonObject])) { (qp, query) =>
                    emit(viewsQuery.query(id, project, query, qp).attemptNarrow[ElasticSearchViewRejection])
                  }
                },
                // Create a point in time for the given view
                (pathPrefix("_pit") & parameter("keep_alive".as[Long]) & post & pathEndOrSingleSlash) { keepAlive =>
                  val keepAliveDuration = Duration(keepAlive, TimeUnit.SECONDS)
                  emit(
                    viewsQuery
                      .createPointInTime(id, project, keepAliveDuration)
                      .map(_.asJson)
                      .attemptNarrow[ElasticSearchViewRejection]
                  )
                },
                // Delete a point in time
                (pathPrefix("_pit") & entity(as[PointInTime]) & delete & pathEndOrSingleSlash) { pit =>
                  emit(
                    StatusCodes.NoContent,
                    viewsQuery.deletePointInTime(pit).attemptNarrow[ElasticSearchViewRejection]
                  )
                },
                // Fetch an elasticsearch view original source
                (pathPrefix("source") & get & pathEndOrSingleSlash & idSegmentRef(id)) { id =>
                  authorizeFor(project, Read).apply {
                    emitSource(views.fetch(id, project))
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
