package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.routes

import akka.http.scaladsl.model.StatusCodes.Created
import akka.http.scaladsl.server.*
import cats.implicits.*
import ch.epfl.bluebrain.nexus.akka.marshalling.CirceUnmarshalling
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StoragePluginExceptionHandler.handleStorageExceptions
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.*
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageRejection
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageRejection.*
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.permissions.{read as Read, write as Write}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaDirectives.*
import ch.epfl.bluebrain.nexus.delta.sdk.directives.{AuthDirectives, DeltaSchemeDirectives}
import ch.epfl.bluebrain.nexus.delta.sdk.fusion.FusionConfig
import ch.epfl.bluebrain.nexus.delta.sdk.identities.Identities
import ch.epfl.bluebrain.nexus.delta.sdk.implicits.*
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.{OriginalSource, RdfMarshalling}
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import io.circe.Json

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
  */
final class StoragesRoutes(
    identities: Identities,
    aclCheck: AclCheck,
    storages: Storages,
    storagesStatistics: StoragesStatistics,
    schemeDirectives: DeltaSchemeDirectives
)(implicit
    baseUri: BaseUri,
    cr: RemoteContextResolution,
    ordering: JsonKeyOrdering,
    fusionConfig: FusionConfig
) extends AuthDirectives(identities, aclCheck)
    with CirceUnmarshalling
    with RdfMarshalling {

  import schemeDirectives.*

  def routes: Route =
    (baseUriPrefix(baseUri.prefix) & replaceUri("storages", schemas.storage)) {
      (handleStorageExceptions & pathPrefix("storages")) {
        extractCaller { implicit caller =>
          projectRef { project =>
            concat(
              pathEndOrSingleSlash {
                // Create a storage without id segment
                (post & noParameter("rev") & entity(as[Json])) { source =>
                  authorizeFor(project, Write).apply {
                    emit(
                      Created,
                      storages.create(project, source).mapValue(_.metadata)
                    )
                  }
                }
              },
              idSegment { id =>
                concat(
                  pathEndOrSingleSlash {
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
                                  .mapValue(_.metadata)
                              )
                            case (Some(rev), source) =>
                              // Update a storage
                              emit(
                                storages
                                  .update(id, project, rev, source)
                                  .mapValue(_.metadata)
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
                  },
                  // Undeprecate a storage
                  (pathPrefix("undeprecate") & pathEndOrSingleSlash & put & parameter("rev".as[Int])) { rev =>
                    authorizeFor(project, Write).apply {
                      emit(
                        storages
                          .undeprecate(id, project, rev)
                          .mapValue(_.metadata)
                          .attemptNarrow[StorageRejection]
                          .rejectOn[StorageNotFound]
                      )
                    }
                  },
                  // Fetch a storage original source
                  (pathPrefix("source") & get & pathEndOrSingleSlash & idSegmentRef(id)) { id =>
                    authorizeFor(project, Read).apply {
                      val sourceIO = storages
                        .fetch(id, project)
                        .map { resource => OriginalSource(resource, resource.value.source) }
                      emit(sourceIO.attemptNarrow[StorageRejection].rejectOn[StorageNotFound])
                    }
                  },
                  (pathPrefix("statistics") & get & pathEndOrSingleSlash) {
                    authorizeFor(project, Read).apply {
                      emit(storagesStatistics.get(id, project))
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
      schemeDirectives: DeltaSchemeDirectives
  )(implicit
      baseUri: BaseUri,
      cr: RemoteContextResolution,
      ordering: JsonKeyOrdering,
      fusionConfig: FusionConfig
  ): Route =
    new StoragesRoutes(identities, aclCheck, storages, storagesStatistics, schemeDirectives).routes

}
