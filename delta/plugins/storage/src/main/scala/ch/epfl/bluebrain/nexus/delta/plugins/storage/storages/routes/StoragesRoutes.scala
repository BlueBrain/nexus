package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.routes

import akka.http.scaladsl.model.StatusCodes.Created
import akka.http.scaladsl.server._
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageRejection._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.{Storage, StorageRejection}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.permissions.{read => Read, write => Write}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.IndexingAction
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.circe.CirceUnmarshalling
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaDirectives._
import ch.epfl.bluebrain.nexus.delta.sdk.directives.{AuthDirectives, DeltaSchemeDirectives}
import ch.epfl.bluebrain.nexus.delta.sdk.fusion.FusionConfig
import ch.epfl.bluebrain.nexus.delta.sdk.identities.Identities
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.RdfMarshalling
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
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
          projectRef { project =>
            concat(
              (pathEndOrSingleSlash & operationName(s"$prefixSegment/storages/{org}/{project}")) {
                // Create a storage without id segment
                (post & noParameter("rev") & entity(as[Json]) & indexingMode) { (source, mode) =>
                  authorizeFor(project, Write).apply {
                    emit(
                      Created,
                      storages
                        .create(project, source)
                        .flatTap(index(project, _, mode))
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
                          authorizeFor(project, Write).apply {
                            (parameter("rev".as[Int].?) & pathEndOrSingleSlash & entity(as[Json])) {
                              case (None, source)      =>
                                // Create a storage with id segment
                                emit(
                                  Created,
                                  storages
                                    .create(id, project, source)
                                    .flatTap(index(project, _, mode))
                                    .mapValue(_.metadata)
                                    .attemptNarrow[StorageRejection]
                                )
                              case (Some(rev), source) =>
                                // Update a storage
                                emit(
                                  storages
                                    .update(id, project, rev, source)
                                    .flatTap(index(project, _, mode))
                                    .mapValue(_.metadata)
                                    .attemptNarrow[StorageRejection]
                                )
                            }
                          }
                        },
                        // Deprecate a storage
                        (delete & parameter("rev".as[Int])) { rev =>
                          authorizeFor(project, Write).apply {
                            emit(
                              storages
                                .deprecate(id, project, rev)
                                .flatTap(index(project, _, mode))
                                .mapValue(_.metadata)
                                .attemptNarrow[StorageRejection]
                                .rejectOn[StorageNotFound]
                            )
                          }
                        },
                        // Fetch a storage
                        (get & idSegmentRef(id)) { id =>
                          emitOrFusionRedirect(
                            project,
                            id,
                            authorizeFor(project, Read).apply {
                              emit(
                                storages
                                  .fetch(id, project)
                                  .attemptNarrow[StorageRejection]
                                  .rejectOn[StorageNotFound]
                              )
                            }
                          )
                        }
                      )
                    }
                  },
                  // Undeprecate a storage
                  (put & pathPrefix("undeprecate") & pathEndOrSingleSlash & parameter("rev".as[Int])) { rev =>
                    authorizeFor(project, Write).apply {
                      emit(
                        storages
                          .undeprecate(id, project, rev)
                          .flatTap(index(project, _, mode))
                          .mapValue(_.metadata)
                          .attemptNarrow[StorageRejection]
                          .rejectOn[StorageNotFound]
                      )
                    }
                  },
                  // Fetch a storage original source
                  (pathPrefix("source") & get & pathEndOrSingleSlash & idSegmentRef(id)) { id =>
                    operationName(s"$prefixSegment/storages/{org}/{project}/{id}/source") {
                      authorizeFor(project, Read).apply {
                        val sourceIO = storages
                          .fetch(id, project)
                          .map(res => res.value.source)
                        emit(sourceIO.attemptNarrow[StorageRejection].rejectOn[StorageNotFound])
                      }
                    }
                  },
                  (pathPrefix("statistics") & get & pathEndOrSingleSlash) {
                    authorizeFor(project, Read).apply {
                      emit(storagesStatistics.get(id, project).attemptNarrow[StorageRejection])
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
