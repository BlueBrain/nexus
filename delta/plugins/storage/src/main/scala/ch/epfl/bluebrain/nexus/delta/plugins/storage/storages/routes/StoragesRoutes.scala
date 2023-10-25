package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.routes

import akka.http.scaladsl.model.StatusCodes.Created
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server._
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.kernel.effect.migration._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageRejection._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.{Storage, StorageRejection}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.permissions.{read => Read, write => Write}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.IndexingAction
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.ce.DeltaDirectives._
import ch.epfl.bluebrain.nexus.delta.sdk.circe.CirceUnmarshalling
import ch.epfl.bluebrain.nexus.delta.sdk.directives.{AuthDirectives, DeltaSchemeDirectives}
import ch.epfl.bluebrain.nexus.delta.sdk.fusion.FusionConfig
import ch.epfl.bluebrain.nexus.delta.sdk.identities.Identities
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.RdfMarshalling
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.model.routes.Tag
import io.circe.Json
import kamon.instrumentation.akka.http.TracingDirectives.operationName

/**
  * The storages routes
  *
  * @param identities
  *   the identity module
  * @param aclCheck
  *   how to check acls
  * @param storages
  *   the storages module
  * @param schemeDirectives
  *   directives related to orgs and projects
  * @param index
  *   the indexing action on write operations
  */
final class StoragesRoutes(
    identities: Identities,
    aclCheck: AclCheck,
    storages: Storages,
    storagesStatistics: StoragesStatistics,
    schemeDirectives: DeltaSchemeDirectives,
    index: IndexingAction.Execute[Storage]
)(implicit
    baseUri: BaseUri,
    cr: RemoteContextResolution,
    ordering: JsonKeyOrdering,
    fusionConfig: FusionConfig
) extends AuthDirectives(identities, aclCheck)
    with CirceUnmarshalling
    with RdfMarshalling {

  import baseUri.prefixSegment
  import schemeDirectives._

  def routes: Route =
    (baseUriPrefix(baseUri.prefix) & replaceUri("storages", schemas.storage)) {
      pathPrefix("storages") {
        extractCaller { implicit caller =>
          resolveProjectRef.apply { implicit ref =>
            concat(
              (pathEndOrSingleSlash & operationName(s"$prefixSegment/storages/{org}/{project}")) {
                // Create a storage without id segment
                (post & noParameter("rev") & entity(as[Json]) & indexingMode) { (source, mode) =>
                  authorizeFor(ref, Write).apply {
                    emit(
                      Created,
                      storages
                        .create(ref, source)
                        .toCatsIO
                        .flatTap(index(ref, _, mode))
                        .mapValue(_.metadata)
                        .attemptNarrow[StorageRejection]
                    )
                  }
                }
              },
              (idSegment & indexingMode) { (id, mode) =>
                concat(
                  pathEndOrSingleSlash {
                    operationName(s"$prefixSegment/storages/{org}/{project}/{id}") {
                      concat(
                        // Create or update a storage
                        put {
                          authorizeFor(ref, Write).apply {
                            (parameter("rev".as[Int].?) & pathEndOrSingleSlash & entity(as[Json])) {
                              case (None, source)      =>
                                // Create a storage with id segment
                                emit(
                                  Created,
                                  storages
                                    .create(id, ref, source)
                                    .toCatsIO
                                    .flatTap(index(ref, _, mode))
                                    .mapValue(_.metadata)
                                    .attemptNarrow[StorageRejection]
                                )
                              case (Some(rev), source) =>
                                // Update a storage
                                emit(
                                  storages
                                    .update(id, ref, rev, source)
                                    .toCatsIO
                                    .flatTap(index(ref, _, mode))
                                    .mapValue(_.metadata)
                                    .attemptNarrow[StorageRejection]
                                )
                            }
                          }
                        },
                        // Deprecate a storage
                        (delete & parameter("rev".as[Int])) { rev =>
                          authorizeFor(ref, Write).apply {
                            emit(
                              storages
                                .deprecate(id, ref, rev)
                                .toCatsIO
                                .flatTap(index(ref, _, mode))
                                .mapValue(_.metadata)
                                .attemptNarrow[StorageRejection]
                                .rejectOn[StorageNotFound]
                            )
                          }
                        },
                        // Fetch a storage
                        (get & idSegmentRef(id)) { id =>
                          emitOrFusionRedirect(
                            ref,
                            id,
                            authorizeFor(ref, Read).apply {
                              emit(
                                storages
                                  .fetch(id, ref)
                                  .toCatsIO
                                  .attemptNarrow[StorageRejection]
                                  .rejectOn[StorageNotFound]
                              )
                            }
                          )
                        }
                      )
                    }
                  },
                  // Fetch a storage original source
                  (pathPrefix("source") & get & pathEndOrSingleSlash & idSegmentRef(id)) { id =>
                    operationName(s"$prefixSegment/storages/{org}/{project}/{id}/source") {
                      authorizeFor(ref, Read).apply {
                        val sourceIO = storages
                          .fetch(id, ref)
                          .toCatsIO
                          .map(res => res.value.source)
                        emit(sourceIO.attemptNarrow[StorageRejection].rejectOn[StorageNotFound])
                      }
                    }
                  },
                  (pathPrefix("tags") & pathEndOrSingleSlash) {
                    operationName(s"$prefixSegment/storages/{org}/{project}/{id}/tags") {
                      concat(
                        // Fetch a storage tags
                        (get & idSegmentRef(id) & authorizeFor(ref, Read)) { id =>
                          emit(
                            storages
                              .fetch(id, ref)
                              .toCatsIO
                              .map(_.value.tags)
                              .attemptNarrow[StorageRejection]
                              .rejectOn[StorageNotFound]
                          )
                        },
                        // Tag a storage
                        (post & parameter("rev".as[Int])) { rev =>
                          authorizeFor(ref, Write).apply {
                            entity(as[Tag]) { case Tag(tagRev, tag) =>
                              emit(
                                Created,
                                storages
                                  .tag(id, ref, tag, tagRev, rev)
                                  .toCatsIO
                                  .flatTap(index(ref, _, mode))
                                  .mapValue(_.metadata)
                                  .attemptNarrow[StorageRejection]
                              )
                            }
                          }
                        }
                      )
                    }
                  },
                  (pathPrefix("statistics") & get & pathEndOrSingleSlash) {
                    authorizeFor(ref, Read).apply {
                      emit(storagesStatistics.get(id, ref).attemptNarrow[StorageRejection])
                    }
                  }
                )
              }
            )
          }
        }
      }
    }
}

object StoragesRoutes {

  /**
    * @return
    *   the [[Route]] for storages
    */
  def apply(
      identities: Identities,
      aclCheck: AclCheck,
      storages: Storages,
      storagesStatistics: StoragesStatistics,
      schemeDirectives: DeltaSchemeDirectives,
      index: IndexingAction.Execute[Storage]
  )(implicit
      baseUri: BaseUri,
      cr: RemoteContextResolution,
      ordering: JsonKeyOrdering,
      fusionConfig: FusionConfig
  ): Route =
    new StoragesRoutes(identities, aclCheck, storages, storagesStatistics, schemeDirectives, index).routes

}
