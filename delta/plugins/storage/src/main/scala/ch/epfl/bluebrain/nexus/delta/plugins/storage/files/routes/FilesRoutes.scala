package ch.epfl.bluebrain.nexus.delta.plugins.storage.files.routes

import akka.http.scaladsl.model.StatusCodes.Created
import akka.http.scaladsl.model.Uri.Path
import akka.http.scaladsl.model.headers.Accept
import akka.http.scaladsl.model.{ContentType, MediaRange}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server._
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.kernel.Mapper
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileRejection
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileRejection._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.permissions.{read => Read, write => Write}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.routes.FilesRoutes._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.{schemas, FileResource, Files}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StoragesConfig.StorageTypeConfig
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.Projects.FetchUuids
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.circe.CirceUnmarshalling
import ch.epfl.bluebrain.nexus.delta.sdk.directives.AuthDirectives
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaDirectives._
import ch.epfl.bluebrain.nexus.delta.sdk.fusion.FusionConfig
import ch.epfl.bluebrain.nexus.delta.sdk.identities.Identities
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.model.routes.{Tag, Tags}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, IdSegment, IdSegmentRef}
import ch.epfl.bluebrain.nexus.delta.sdk.organizations.Organizations
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.Permissions.events
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import io.circe.Decoder
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredDecoder
import kamon.instrumentation.akka.http.TracingDirectives.operationName
import monix.bio.IO
import monix.execution.Scheduler

import scala.annotation.nowarn

/**
  * The files routes
  *
  * @param identities
  *   the identity module
  * @param aclCheck
  *   to check acls
  * @param organizations
  *   the organizations module
  * @param projects
  *   the projects module
  * @param files
  *   the files module
  * @param index
  *   the indexing action on write operations
  */
final class FilesRoutes(
    identities: Identities,
    aclCheck: AclCheck,
    organizations: Organizations,
    projects: Projects,
    files: Files,
    index: IndexingAction
)(implicit
    baseUri: BaseUri,
    storageConfig: StorageTypeConfig,
    s: Scheduler,
    cr: RemoteContextResolution,
    ordering: JsonKeyOrdering,
    fusionConfig: FusionConfig
) extends AuthDirectives(identities, aclCheck)
    with CirceUnmarshalling {

  import baseUri.prefixSegment

  implicit private val fetchProjectUuids: FetchUuids = projects

  implicit private val eventExchangeMapper = Mapper(Files.eventExchangeValue(_))

  def routes: Route =
    (baseUriPrefix(baseUri.prefix) & replaceUri("files", schemas.files, projects)) {
      pathPrefix("files") {
        extractCaller { implicit caller =>
          concat(
            // SSE files for all events
            (pathPrefix("events") & pathEndOrSingleSlash) {
              get {
                operationName(s"$prefixSegment/files/events") {
                  authorizeFor(AclAddress.Root, events.read).apply {
                    lastEventId { offset =>
                      emit(files.events(offset))
                    }
                  }
                }
              }
            },
            // SSE files for all events belonging to an organization
            (orgLabel(organizations) & pathPrefix("events") & pathEndOrSingleSlash) { org =>
              get {
                operationName(s"$prefixSegment/files/{org}/events") {
                  authorizeFor(org, events.read).apply {
                    lastEventId { offset =>
                      emit(files.events(org, offset).leftWiden[FileRejection])
                    }
                  }
                }
              }
            },
            projectRef(projects).apply { ref =>
              concat(
                // SSE files for all events belonging to a project
                (pathPrefix("events") & pathEndOrSingleSlash) {
                  get {
                    operationName(s"$prefixSegment/files/{org}/{project}/events") {
                      authorizeFor(ref, events.read).apply {
                        lastEventId { offset =>
                          emit(files.events(ref, offset))
                        }
                      }
                    }
                  }
                },
                (post & pathEndOrSingleSlash & noParameter("rev") & parameter(
                  "storage".as[IdSegment].?
                ) & indexingMode) { (storage, mode) =>
                  operationName(s"$prefixSegment/files/{org}/{project}") {
                    concat(
                      // Link a file without id segment
                      entity(as[LinkFile]) { case LinkFile(filename, mediaType, path) =>
                        emit(
                          Created,
                          files.createLink(storage, ref, filename, mediaType, path).tapEval(index(ref, _, mode))
                        )
                      },
                      // Create a file without id segment
                      extractRequestEntity { entity =>
                        emit(Created, files.create(storage, ref, entity).tapEval(index(ref, _, mode)))
                      }
                    )
                  }
                },
                (idSegment & indexingMode) { (id, mode) =>
                  concat(
                    pathEndOrSingleSlash {
                      operationName(s"$prefixSegment/files/{org}/{project}/{id}") {
                        concat(
                          (put & pathEndOrSingleSlash) {
                            parameters("rev".as[Long].?, "storage".as[IdSegment].?) {
                              case (None, storage)      =>
                                concat(
                                  // Link a file with id segment
                                  entity(as[LinkFile]) { case LinkFile(filename, mediaType, path) =>
                                    emit(
                                      Created,
                                      files
                                        .createLink(id, storage, ref, filename, mediaType, path)
                                        .tapEval(index(ref, _, mode))
                                    )
                                  },
                                  // Create a file with id segment
                                  extractRequestEntity { entity =>
                                    emit(Created, files.create(id, storage, ref, entity).tapEval(index(ref, _, mode)))
                                  }
                                )
                              case (Some(rev), storage) =>
                                concat(
                                  // Update a Link
                                  entity(as[LinkFile]) { case LinkFile(filename, mediaType, path) =>
                                    emit(
                                      files
                                        .updateLink(id, storage, ref, filename, mediaType, path, rev)
                                        .tapEval(index(ref, _, mode))
                                    )
                                  },
                                  // Update a file
                                  extractRequestEntity { entity =>
                                    emit(files.update(id, storage, ref, rev, entity).tapEval(index(ref, _, mode)))
                                  }
                                )
                            }
                          },
                          // Deprecate a file
                          (delete & parameter("rev".as[Long])) { rev =>
                            authorizeFor(ref, Write).apply {
                              emit(files.deprecate(id, ref, rev).tapEval(index(ref, _, mode)).rejectOn[FileNotFound])
                            }
                          },
                          // Fetch a file
                          (get & idSegmentRef(id)) { id =>
                            emitOrFusionRedirect(
                              ref,
                              id,
                              fetch(id, ref)
                            )
                          }
                        )
                      }
                    },
                    (pathPrefix("tags")) {
                      operationName(s"$prefixSegment/files/{org}/{project}/{id}/tags") {
                        concat(
                          // Fetch a file tags
                          (get & idSegmentRef(id) & pathEndOrSingleSlash & authorizeFor(ref, Read)) { id =>
                            emit(fetchMetadata(id, ref).map(res => Tags(res.value.tags)).rejectOn[FileNotFound])
                          },
                          // Tag a file
                          (post & parameter("rev".as[Long]) & pathEndOrSingleSlash) { rev =>
                            authorizeFor(ref, Write).apply {
                              entity(as[Tag]) { case Tag(tagRev, tag) =>
                                emit(Created, files.tag(id, ref, tag, tagRev, rev).tapEval(index(ref, _, mode)))
                              }
                            }
                          },
                          // Delete a tag
                          (tagLabel & delete & parameter("rev".as[Long]) & pathEndOrSingleSlash & authorizeFor(
                            ref,
                            Write
                          )) { (tag, rev) =>
                            emit(files.deleteTag(id, ref, tag, rev).tapEval(index(ref, _, mode)))
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

  def fetch(id: IdSegmentRef, ref: ProjectRef)(implicit caller: Caller): Route =
    headerValueByType(Accept) {
      case accept if accept.mediaRanges.exists(metadataMediaRanges.contains) =>
        emit(fetchMetadata(id, ref).rejectOn[FileNotFound])
      case _                                                                 =>
        emit(files.fetchContent(id, ref).rejectOn[FileNotFound])
    }

  def fetchMetadata(id: IdSegmentRef, ref: ProjectRef)(implicit caller: Caller): IO[FileRejection, FileResource] =
    aclCheck.authorizeForOr(ref, Read)(AuthorizationFailed(ref, Read)) >> files.fetch(id, ref)
}

object FilesRoutes {

  // If accept header media range exactly match one of these, we return file metadata,
  // otherwise we return the file content
  val metadataMediaRanges: Set[MediaRange] = mediaTypes.map(_.toContentType.mediaType: MediaRange).toSet

  /**
    * @return
    *   the [[Route]] for files
    */
  def apply(
      config: StorageTypeConfig,
      identities: Identities,
      aclCheck: AclCheck,
      organizations: Organizations,
      projects: Projects,
      files: Files,
      index: IndexingAction
  )(implicit
      baseUri: BaseUri,
      s: Scheduler,
      cr: RemoteContextResolution,
      ordering: JsonKeyOrdering,
      fusionConfig: FusionConfig
  ): Route = {
    implicit val storageTypeConfig: StorageTypeConfig = config
    new FilesRoutes(identities, aclCheck, organizations, projects, files, index).routes
  }

  final case class LinkFile(filename: Option[String], mediaType: Option[ContentType], path: Path)
  object LinkFile {
    import ch.epfl.bluebrain.nexus.delta.plugins.storage.instances._
    import ch.epfl.bluebrain.nexus.delta.rdf.instances._
    @nowarn("cat=unused")
    implicit private val config: Configuration      = Configuration.default.withStrictDecoding
    implicit val linkFileDecoder: Decoder[LinkFile] = deriveConfiguredDecoder[LinkFile]
  }
}
