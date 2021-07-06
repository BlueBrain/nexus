package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.routes

import akka.http.scaladsl.model.StatusCodes.Created
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server._
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.kernel.Secret
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageRejection._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.{Storage, StorageRejection, StorageSearchParams}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.{schemas, StorageResource, Storages, StoragesConfig}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.permissions.{read => Read, write => Write}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.Permissions.events
import ch.epfl.bluebrain.nexus.delta.sdk.Projects.FetchUuids
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.circe.CirceUnmarshalling
import ch.epfl.bluebrain.nexus.delta.sdk.crypto.Crypto
import ch.epfl.bluebrain.nexus.delta.sdk.directives.AuthDirectives
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaDirectives._
import ch.epfl.bluebrain.nexus.delta.sdk.directives.UriDirectives.searchParams
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.RdfMarshalling
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.routes.{Tag, Tags}
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults.searchResultsJsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.{PaginationConfig, SearchResults}
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import io.circe.Json
import kamon.instrumentation.akka.http.TracingDirectives.operationName
import monix.execution.Scheduler

/**
  * The storages routes
  *
  * @param identities    the identity module
  * @param acls          the acls module
  * @param organizations the organizations module
  * @param projects      the projects module
  * @param storages      the storages module
  */
final class StoragesRoutes(
    identities: Identities,
    acls: Acls,
    organizations: Organizations,
    projects: Projects,
    storages: Storages
)(implicit
    baseUri: BaseUri,
    crypto: Crypto,
    paginationConfig: PaginationConfig,
    s: Scheduler,
    cr: RemoteContextResolution,
    ordering: JsonKeyOrdering
) extends AuthDirectives(identities, acls)
    with CirceUnmarshalling
    with RdfMarshalling {

  import baseUri.prefixSegment

  implicit private val fetchProjectUuids: FetchUuids = projects

  private def storagesSearchParams(implicit projectRef: ProjectRef, caller: Caller): Directive1[StorageSearchParams] = {
    (searchParams & types(projects)).tflatMap { case (deprecated, rev, createdBy, updatedBy, types) =>
      callerAcls.map { aclsCol =>
        StorageSearchParams(
          Some(projectRef),
          deprecated,
          rev,
          createdBy,
          updatedBy,
          types,
          storage => aclsCol.exists(caller.identities, Read, storage.project)
        )
      }
    }
  }

  @SuppressWarnings(Array("OptionGet"))
  def routes: Route =
    (baseUriPrefix(baseUri.prefix) & replaceUri("storages", schemas.storage, projects)) {
      pathPrefix("storages") {
        extractCaller { implicit caller =>
          concat(
            // SSE storages for all events
            (pathPrefix("events") & pathEndOrSingleSlash) {
              get {
                operationName(s"$prefixSegment/storages/events") {
                  authorizeFor(AclAddress.Root, events.read).apply {
                    lastEventId { offset =>
                      emit(storages.events(offset))
                    }
                  }
                }
              }
            },
            // SSE storages for all events belonging to an organization
            (orgLabel(organizations) & pathPrefix("events") & pathEndOrSingleSlash) { org =>
              get {
                operationName(s"$prefixSegment/storages/{org}/events") {
                  authorizeFor(org, events.read).apply {
                    lastEventId { offset =>
                      emit(storages.events(org, offset).leftWiden[StorageRejection])
                    }
                  }
                }
              }
            },
            projectRef(projects).apply { implicit ref =>
              concat(
                // SSE storages for all events belonging to a project
                (pathPrefix("events") & pathEndOrSingleSlash) {
                  get {
                    operationName(s"$prefixSegment/storages/{org}/{project}/events") {
                      authorizeFor(ref, events.read).apply {
                        lastEventId { offset =>
                          emit(storages.events(ref, offset))
                        }
                      }
                    }
                  }
                },
                (pathEndOrSingleSlash & operationName(s"$prefixSegment/storages/{org}/{project}")) {
                  // Create a storage without id segment
                  (post & noParameter("rev") & entity(as[Json]) & executionType) { (source, execution) =>
                    authorizeFor(ref, Write).apply {
                      emit(Created, storages.create(ref, Secret(source), execution).mapValue(_.metadata))
                    }
                  }
                },
                (pathPrefix("caches") & pathEndOrSingleSlash) {
                  operationName(s"$prefixSegment/storages/{org}/{project}/caches") {
                    // List storages in cache
                    (get & extractUri & fromPaginated & storagesSearchParams & sort[Storage]) {
                      (uri, pagination, params, order) =>
                        authorizeFor(ref, Read).apply {
                          implicit val searchJsonLdEncoder: JsonLdEncoder[SearchResults[StorageResource]] =
                            searchResultsJsonLdEncoder(Storages.context, pagination, uri)

                          emit(storages.list(pagination, params, order).widen[SearchResults[StorageResource]])
                        }
                    }
                  }
                },
                (idSegment & executionType) { (id, execution) =>
                  concat(
                    pathEndOrSingleSlash {
                      operationName(s"$prefixSegment/storages/{org}/{project}/{id}") {
                        concat(
                          // Create or update a storage
                          put {
                            authorizeFor(ref, Write).apply {
                              (parameter("rev".as[Long].?) & pathEndOrSingleSlash & entity(as[Json])) {
                                case (None, source)      =>
                                  // Create a storage with id segment
                                  emit(
                                    Created,
                                    storages.create(id, ref, Secret(source), execution).mapValue(_.metadata)
                                  )
                                case (Some(rev), source) =>
                                  // Update a storage
                                  emit(storages.update(id, ref, rev, Secret(source), execution).mapValue(_.metadata))
                              }
                            }
                          },
                          // Deprecate a storage
                          (delete & parameter("rev".as[Long])) { rev =>
                            authorizeFor(ref, Write).apply {
                              emit(storages.deprecate(id, ref, rev, execution).mapValue(_.metadata))
                            }
                          },
                          // Fetch a storage
                          (get & idSegmentRef(id) & authorizeFor(ref, Read)) { id =>
                            emit(storages.fetch(id, ref).leftWiden[StorageRejection].rejectOn[StorageNotFound])
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
                            .map(res => Storage.encryptSource(res.value.source, crypto).toOption.get)
                          emit(sourceIO.leftWiden[StorageRejection].rejectOn[StorageNotFound])
                        }
                      }
                    },
                    (pathPrefix("tags") & pathEndOrSingleSlash) {
                      operationName(s"$prefixSegment/storages/{org}/{project}/{id}/tags") {
                        concat(
                          // Fetch a storage tags
                          (get & idSegmentRef(id) & authorizeFor(ref, Read)) { id =>
                            val tagsIO = storages.fetch(id, ref).map(res => Tags(res.value.tags))
                            emit(tagsIO.leftWiden[StorageRejection].rejectOn[StorageNotFound])
                          },
                          // Tag a storage
                          (post & parameter("rev".as[Long])) { rev =>
                            authorizeFor(ref, Write).apply {
                              entity(as[Tag]) { case Tag(tagRev, tag) =>
                                emit(Created, storages.tag(id, ref, tag, tagRev, rev, execution).mapValue(_.metadata))
                              }
                            }
                          }
                        )
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

object StoragesRoutes {

  /**
    * @return the [[Route]] for storages
    */
  def apply(
      config: StoragesConfig,
      identities: Identities,
      acls: Acls,
      organizations: Organizations,
      projects: Projects,
      storages: Storages
  )(implicit
      baseUri: BaseUri,
      s: Scheduler,
      cr: RemoteContextResolution,
      ordering: JsonKeyOrdering,
      crypto: Crypto
  ): Route = {
    implicit val paginationConfig: PaginationConfig = config.pagination
    new StoragesRoutes(identities, acls, organizations, projects, storages).routes
  }

}
