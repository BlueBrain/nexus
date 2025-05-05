package ch.epfl.bluebrain.nexus.delta.plugins.storage.files.routes

import akka.http.scaladsl.model.MediaRange
import akka.http.scaladsl.model.StatusCodes.Created
import akka.http.scaladsl.model.headers.{`Content-Length`, Accept}
import akka.http.scaladsl.server.Directives.{extractRequestEntity, optionalHeaderValueByName, provide, reject}
import akka.http.scaladsl.server.*
import cats.effect.IO
import cats.syntax.all.*
import ch.epfl.bluebrain.nexus.akka.marshalling.CirceUnmarshalling
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileRejection.*
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.*
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.permissions.{read as Read, write as Write}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.routes.FileUriDirectives.*
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.routes.FilesRoutes.*
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.{schemas, FileResource, Files}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StoragePluginExceptionHandler.handleStorageExceptions
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StoragesConfig.ShowFileLocation
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.*
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaDirectives.*
import ch.epfl.bluebrain.nexus.delta.sdk.directives.{AuthDirectives, DeltaSchemeDirectives}
import ch.epfl.bluebrain.nexus.delta.sdk.error.ServiceError.AuthorizationFailed
import ch.epfl.bluebrain.nexus.delta.sdk.fusion.FusionConfig
import ch.epfl.bluebrain.nexus.delta.sdk.identities.Identities
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.implicits.*
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.model.routes.Tag
import io.circe.parser

/**
  * The files routes
  *
  * @param identities
  *   the identity module
  * @param aclCheck
  *   to check acls
  * @param files
  *   the files module
  * @param schemeDirectives
  *   directives related to orgs and projects
  * @param index
  *   the indexing action on write operations
  */
final class FilesRoutes(
    identities: Identities,
    aclCheck: AclCheck,
    files: Files,
    schemeDirectives: DeltaSchemeDirectives,
    index: IndexingAction.Execute[File]
)(implicit
    baseUri: BaseUri,
    showLocation: ShowFileLocation,
    cr: RemoteContextResolution,
    ordering: JsonKeyOrdering,
    fusionConfig: FusionConfig
) extends AuthDirectives(identities, aclCheck)
    with CirceUnmarshalling { self =>

  import schemeDirectives.*

  def routes: Route =
    (baseUriPrefix(baseUri.prefix) & replaceUri("files", schemas.files)) {
      (handleStorageExceptions & pathPrefix("files")) {
        extractCaller { implicit caller =>
          projectRef { project =>
            implicit class IndexOps(io: IO[FileResource]) {
              def index(m: IndexingMode): IO[FileResource] = io.flatTap(self.index(project, _, m))
            }
            concat(
              (pathEndOrSingleSlash & post & noRev & storageParam & indexingMode & tagParam) { (storage, mode, tag) =>
                concat(
                  // Create a file without id segment
                  uploadRequest { request =>
                    emit(
                      Created,
                      files.create(storage, project, request, tag).index(mode)
                    )
                  }
                )
              },
              (idSegment & indexingMode) { (id, mode) =>
                val fileId = FileId(id, project)
                concat(
                  pathEndOrSingleSlash {
                    concat(
                      (put & pathEndOrSingleSlash) {
                        concat(
                          (revParam & storageParam & tagParam) { case (rev, storage, tag) =>
                            concat(
                              // Update a file
                              (requestEntityPresent & uploadRequest) { request =>
                                emit(
                                  files
                                    .update(fileId, storage, rev, request, tag)
                                    .index(mode)
                                )
                              },
                              // Update custom metadata
                              (requestEntityEmpty & extractFileMetadata & authorizeFor(project, Write)) {
                                case Some(FileCustomMetadata.empty) =>
                                  emit(IO.raiseError[FileResource](EmptyCustomMetadata))
                                case Some(metadata)                 =>
                                  emit(files.updateMetadata(fileId, rev, metadata, tag).index(mode))
                                case None                           => reject
                              }
                            )
                          },
                          (storageParam & tagParam) { case (storage, tag) =>
                            concat(
                              // Create a file with id segment
                              uploadRequest { request =>
                                emit(
                                  Created,
                                  files
                                    .create(fileId, storage, request, tag)
                                    .index(mode)
                                )
                              }
                            )
                          }
                        )
                      },
                      // Deprecate a file
                      (delete & revParam) { rev =>
                        authorizeFor(project, Write).apply {
                          emit(
                            files
                              .deprecate(fileId, rev)
                              .index(mode)
                              .attemptNarrow[FileRejection]
                              .rejectOn[FileNotFound]
                          )
                        }
                      },

                      // Fetch a file
                      (get & idSegmentRef(id)) { id =>
                        emitOrFusionRedirect(project, id, fetch(FileId(id, project)))
                      }
                    )
                  },
                  pathPrefix("tags") {
                    concat(
                      // Fetch a file tags
                      (get & idSegmentRef(id) & pathEndOrSingleSlash & authorizeFor(project, Read)) { id =>
                        emit(
                          fetchMetadata(FileId(id, project))
                            .map(_.value.tags)
                            .attemptNarrow[FileRejection]
                            .rejectOn[FileNotFound]
                        )
                      },
                      // Tag a file
                      (post & revParam & pathEndOrSingleSlash) { rev =>
                        authorizeFor(project, Write).apply {
                          entity(as[Tag]) { case Tag(tagRev, tag) =>
                            emit(Created, files.tag(fileId, tag, tagRev, rev).index(mode))
                          }
                        }
                      },
                      // Delete a tag
                      (tagLabel & delete & revParam & pathEndOrSingleSlash & authorizeFor(
                        project,
                        Write
                      )) { (tag, rev) =>
                        emit(
                          files
                            .deleteTag(fileId, tag, rev)
                            .index(mode)
                            .attemptNarrow[FileRejection]
                            .rejectOn[FileNotFound]
                        )
                      }
                    )
                  },
                  (pathPrefix("undeprecate") & put & revParam) { rev =>
                    authorizeFor(project, Write).apply {
                      emit(
                        files
                          .undeprecate(fileId, rev)
                          .index(mode)
                          .attemptNarrow[FileRejection]
                          .rejectOn[FileNotFound]
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

  def fetch(id: FileId)(implicit caller: Caller): Route =
    (headerValueByType(Accept) & varyAcceptHeaders) {
      case accept if accept.mediaRanges.exists(metadataMediaRanges.contains) =>
        emit(fetchMetadata(id).attemptNarrow[FileRejection].rejectOn[FileNotFound])
      case _                                                                 =>
        emit(files.fetchContent(id).attemptNarrow[FileRejection].rejectOn[FileNotFound])
    }

  def fetchMetadata(id: FileId)(implicit caller: Caller): IO[FileResource] =
    aclCheck.authorizeForOr(id.project, Read)(AuthorizationFailed(id.project, Read)) >> files.fetch(id)
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
      identities: Identities,
      aclCheck: AclCheck,
      files: Files,
      schemeDirectives: DeltaSchemeDirectives,
      index: IndexingAction.Execute[File]
  )(implicit
      baseUri: BaseUri,
      showLocation: ShowFileLocation,
      cr: RemoteContextResolution,
      ordering: JsonKeyOrdering,
      fusionConfig: FusionConfig
  ): Route = new FilesRoutes(identities, aclCheck, files, schemeDirectives, index).routes

  /**
    * An akka directive to extract the optional [[FileCustomMetadata]] from a request. This metadata is extracted from
    * the `x-nxs-file-metadata` header. In case the decoding fails, a [[MalformedHeaderRejection]] is returned.
    */
  def extractFileMetadata: Directive1[Option[FileCustomMetadata]] =
    optionalHeaderValueByName(NexusHeaders.fileMetadata).flatMap {
      case Some(metadata) =>
        val md = parser.parse(metadata).flatMap(_.as[FileCustomMetadata])
        md match {
          case Right(value) => provide(Some(value))
          case Left(err)    => reject(MalformedHeaderRejection(NexusHeaders.fileMetadata, err.getMessage))
        }
      case None           => provide(Some(FileCustomMetadata.empty))
    }

  def fileContentLength: Directive1[Option[Long]] = {
    optionalHeaderValueByName(NexusHeaders.fileContentLength).flatMap {
      case Some(value) =>
        value.toLongOption match {
          case None =>
            val msg =
              s"Invalid '${NexusHeaders.fileContentLength}' header value '$value', expected a Long value."
            reject(MalformedHeaderRejection(`Content-Length`.name, msg))
          case v    => provide(v)
        }
      case None        => provide(None)
    }
  }

  def uploadRequest: Directive1[FileUploadRequest] =
    (extractRequestEntity & extractFileMetadata & fileContentLength).tflatMap {
      case (entity, customMetadata, contentLength) =>
        provide(FileUploadRequest(entity, customMetadata, contentLength))
    }
}
