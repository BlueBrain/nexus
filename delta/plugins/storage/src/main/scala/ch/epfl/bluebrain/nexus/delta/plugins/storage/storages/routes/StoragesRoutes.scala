package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.routes

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.StatusCodes.Created
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server._
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageRejection._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.{Crypto, Secret, Storage, StorageRejection, StorageSearchParams}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.routes.StoragesRoutes.responseFieldsStorages
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.{permissions, StorageResource, Storages, StoragesConfig}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.Permissions.events
import ch.epfl.bluebrain.nexus.delta.sdk.Projects.FetchProject
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.circe.CirceUnmarshalling
import ch.epfl.bluebrain.nexus.delta.sdk.directives.AuthDirectives
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaDirectives._
import ch.epfl.bluebrain.nexus.delta.sdk.directives.UriDirectives.searchParams
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.HttpResponseFields
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.HttpResponseFields.{responseFieldsOrganizations, responseFieldsProjects}
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.RdfRejectionHandler._
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.routes.{JsonSource, Tag, Tags}
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.PaginationConfig
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults.{searchResultsEncoder, SearchEncoder}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, IdSegment, TagLabel}
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
    with CirceUnmarshalling {

  import baseUri.prefixSegment
  implicit private val storageContext: ContextValue = Storages.context
  implicit private val fetchProject: FetchProject   = projects.fetch(_)

  private def storagesSearchParams(implicit projectRef: ProjectRef, caller: Caller): Directive1[StorageSearchParams] = {
    (searchParams & types).tflatMap { case (deprecated, rev, createdBy, updatedBy, types) =>
      callerAcls.map { aclsCol =>
        StorageSearchParams(
          Some(projectRef),
          deprecated,
          rev,
          createdBy,
          updatedBy,
          types,
          storage => aclsCol.exists(caller.identities, permissions.read, AclAddress.Project(storage.project))
        )
      }
    }
  }

  @SuppressWarnings(Array("OptionGet"))
  def routes: Route                                                          =
    baseUriPrefix(baseUri.prefix) {
      extractCaller { implicit caller =>
        pathPrefix("storages") {
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
                  authorizeFor(AclAddress.Organization(org), events.read).apply {
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
                      authorizeFor(AclAddress.Project(ref), events.read).apply {
                        lastEventId { offset =>
                          emit(storages.events(ref, offset))
                        }
                      }
                    }
                  }
                },
                (pathEndOrSingleSlash & operationName(s"$prefixSegment/storages/{org}/{project}")) {
                  concat(
                    // Create a storage without id segment
                    (post & noParameter("rev") & entity(as[Json])) { source =>
                      authorizeFor(AclAddress.Project(ref), permissions.write).apply {
                        emit(Created, storages.create(ref, Secret(source)).map(_.void))
                      }
                    },
                    // List storages
                    (get & extractUri & paginated & storagesSearchParams & sort[Storage]) {
                      (uri, pagination, params, order) =>
                        authorizeFor(AclAddress.Project(ref), permissions.read).apply {
                          implicit val searchEncoder: SearchEncoder[StorageResource] =
                            searchResultsEncoder(pagination, uri)
                          emit(storages.list(pagination, params, order))
                        }
                    }
                  )
                },
                idSegment { id =>
                  concat(
                    pathEndOrSingleSlash {
                      operationName(s"$prefixSegment/storages/{org}/{project}/{id}") {
                        concat(
                          // Create or update a storage
                          put {
                            authorizeFor(AclAddress.Project(ref), permissions.write).apply {
                              (parameter("rev".as[Long].?) & pathEndOrSingleSlash & entity(as[Json])) {
                                case (None, source)      =>
                                  // Create a storage with id segment
                                  emit(Created, storages.create(id, ref, Secret(source)).map(_.void))
                                case (Some(rev), source) =>
                                  // Update a storage
                                  emit(storages.update(id, ref, rev, Secret(source)).map(_.void))
                              }
                            }
                          },
                          // Deprecate a storage
                          (delete & parameter("rev".as[Long])) { rev =>
                            authorizeFor(AclAddress.Project(ref), permissions.write).apply {
                              emit(storages.deprecate(id, ref, rev).map(_.void))
                            }
                          },
                          // Fetch a storage
                          get {
                            fetch(id, ref)
                          }
                        )
                      }
                    },
                    // Fetch a storage original source
                    (pathPrefix("source") & get & pathEndOrSingleSlash) {
                      operationName(s"$prefixSegment/storages/{org}/{project}/{id}/source") {
                        fetchMap(
                          id,
                          ref,
                          res => JsonSource(Storage.encryptSource(res.value.source, crypto).toOption.get, res.value.id)
                        )
                      }
                    },
                    (pathPrefix("tags") & pathEndOrSingleSlash) {
                      operationName(s"$prefixSegment/storages/{org}/{project}/{id}/tags") {
                        concat(
                          // Fetch a storage tags
                          get {
                            fetchMap(id, ref, resource => Tags(resource.value.tags))
                          },
                          // Tag a storage
                          (post & parameter("rev".as[Long])) { rev =>
                            authorizeFor(AclAddress.Project(ref), permissions.write).apply {
                              entity(as[Tag]) { case Tag(tagRev, tag) =>
                                emit(Created, storages.tag(id, ref, tag, tagRev, rev).map(_.void))
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
  private def fetch(id: IdSegment, ref: ProjectRef)(implicit caller: Caller) =
    fetchMap(id, ref, identity)

  private def fetchMap[A: JsonLdEncoder](id: IdSegment, ref: ProjectRef, f: StorageResource => A)(implicit
      caller: Caller
  ): Route =
    authorizeFor(AclAddress.Project(ref), permissions.read).apply {
      (parameter("rev".as[Long].?) & parameter("tag".as[TagLabel].?)) {
        case (Some(_), Some(_)) => emit(simultaneousTagAndRevRejection)
        case (Some(rev), _)     => emit(storages.fetchAt(id, ref, rev).leftWiden[StorageRejection].map(f))
        case (_, Some(tag))     => emit(storages.fetchBy(id, ref, tag).leftWiden[StorageRejection].map(f))
        case _                  => emit(storages.fetch(id, ref).leftWiden[StorageRejection].map(f))
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
      ordering: JsonKeyOrdering
  ): Route = {
    implicit val paginationConfig: PaginationConfig = config.pagination
    implicit val crypto: Crypto                     = config.storageTypeConfig.encryption.crypto
    new StoragesRoutes(identities, acls, organizations, projects, storages).routes
  }

  implicit val responseFieldsStorages: HttpResponseFields[StorageRejection] =
    HttpResponseFields {
      case RevisionNotFound(_, _)            => StatusCodes.NotFound
      case TagNotFound(_)                    => StatusCodes.NotFound
      case StorageNotFound(_, _)             => StatusCodes.NotFound
      case DefaultStorageNotFound(_)         => StatusCodes.NotFound
      case StorageAlreadyExists(_, _)        => StatusCodes.Conflict
      case IncorrectRev(_, _)                => StatusCodes.Conflict
      case WrappedProjectRejection(rej)      => rej.status
      case WrappedOrganizationRejection(rej) => rej.status
      case StorageNotAccessible(_, _)        => StatusCodes.Forbidden
      case InvalidEncryptionSecrets(_, _)    => StatusCodes.InternalServerError
      case UnexpectedInitialState(_, _)      => StatusCodes.InternalServerError
      case _                                 => StatusCodes.BadRequest
    }

}
