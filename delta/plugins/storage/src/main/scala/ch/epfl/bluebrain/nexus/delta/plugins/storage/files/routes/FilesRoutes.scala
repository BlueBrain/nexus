package ch.epfl.bluebrain.nexus.delta.plugins.storage.files.routes

import akka.http.scaladsl.model.StatusCodes.Created
import akka.http.scaladsl.model.Uri.Path
import akka.http.scaladsl.model.headers.Accept
import akka.http.scaladsl.model.{ContentType, MediaRange}
import akka.http.scaladsl.server.Directives.{optionalHeaderValueByName, provide, reject}
import akka.http.scaladsl.server._
import cats.effect.IO
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileRejection._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.permissions.{read => Read, write => Write}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.routes.FilesRoutes.LinkFileRequest.{fileDescriptionFromRequest, linkFileDecoder}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.routes.FilesRoutes._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.{schemas, FileResource, Files}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StoragesConfig.ShowFileLocation
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.circe.CirceUnmarshalling
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaDirectives._
import ch.epfl.bluebrain.nexus.delta.sdk.directives.{AuthDirectives, DeltaSchemeDirectives}
import ch.epfl.bluebrain.nexus.delta.sdk.error.ServiceError.AuthorizationFailed
import ch.epfl.bluebrain.nexus.delta.sdk.fusion.FusionConfig
import ch.epfl.bluebrain.nexus.delta.sdk.identities.Identities
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.model.routes.Tag
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, IdSegment}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredDecoder
import io.circe.{parser, Decoder}
import kamon.instrumentation.akka.http.TracingDirectives.operationName

import scala.annotation.nowarn

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

  import baseUri.prefixSegment
  import schemeDirectives._

  def routes: Route =
    (baseUriPrefix(baseUri.prefix) & replaceUri("files", schemas.files)) {
      pathPrefix("files") {
        extractCaller { implicit caller =>
          projectRef { project =>
            implicit class IndexOps(io: IO[FileResource]) {
              def index(m: IndexingMode): IO[FileResource] = io.flatTap(self.index(project, _, m))
            }

            concat(
              (pathEndOrSingleSlash & post & noParameter("rev") & parameter(
                "storage".as[IdSegment].?
              ) & indexingMode & tagParam) { (storage, mode, tag) =>
                operationName(s"$prefixSegment/files/{org}/{project}") {
                  concat(
                    // Link a file without id segment
                    entity(as[LinkFileRequest]) { linkRequest =>
                      emit(
                        Created,
                        fileDescriptionFromRequest(linkRequest)
                          .flatMap { desc =>
                            files
                              .createLink(storage, project, desc, linkRequest.path, tag)
                              .index(mode)
                          }
                          .attemptNarrow[FileRejection]
                      )
                    },
                    // Create a file without id segment
                    (extractRequestEntity & extractFileMetadata) { (entity, metadata) =>
                      emit(
                        Created,
                        files.create(storage, project, entity, tag, metadata).index(mode).attemptNarrow[FileRejection]
                      )
                    }
                  )
                }
              },
              (pathPrefix("register-s3") & post & noParameter("rev") & parameter(
                "storage".as[IdSegment].?
              ) & indexingMode & tagParam) { (storage, mode, tag) =>
                operationName(s"$prefixSegment/files/{org}/{project}/register-s3") {
                  // Register a file from an S3 bucket, path is relative to the bucket's root
                  entity(as[LinkFileRequest]) { linkRequest =>
                    emit(
                      Created,
                      fileDescriptionFromRequest(linkRequest)
                        .flatMap { desc =>
                          files
                            .createLink(storage, project, desc, linkRequest.path, tag)
                            .index(mode)
                        }
                        .attemptNarrow[FileRejection]
                    )
                  }
                }
              },
              (idSegment & indexingMode) { (id, mode) =>
                val fileId = FileId(id, project)
                concat(
                  pathEndOrSingleSlash {
                    operationName(s"$prefixSegment/files/{org}/{project}/{id}") {
                      concat(
                        (put & pathEndOrSingleSlash) {
                          concat(
                            parameters("rev".as[Int], "storage".as[IdSegment].?, "tag".as[UserTag].?) {
                              case (rev, storage, tag) =>
                                concat(
                                  // Update a Link
                                  entity(as[LinkFileRequest]) { linkRequest =>
                                    emit(
                                      fileDescriptionFromRequest(linkRequest)
                                        .flatMap { description =>
                                          files
                                            .updateLink(
                                              fileId,
                                              storage,
                                              description,
                                              linkRequest.path,
                                              rev,
                                              tag
                                            )
                                            .index(mode)
                                        }
                                        .attemptNarrow[FileRejection]
                                    )
                                  },
                                  // Update a file
                                  (requestEntityPresent & extractRequestEntity & extractFileMetadata) {
                                    (entity, metadata) =>
                                      emit(
                                        files
                                          .update(fileId, storage, rev, entity, tag, metadata)
                                          .index(mode)
                                          .attemptNarrow[FileRejection]
                                      )
                                  },
                                  // Update custom metadata
                                  (requestEntityEmpty & extractFileMetadata & authorizeFor(project, Write)) {
                                    case Some(FileCustomMetadata.empty) =>
                                      emit(
                                        IO.raiseError[FileResource](EmptyCustomMetadata).attemptNarrow[FileRejection]
                                      )
                                    case Some(metadata)                 =>
                                      emit(
                                        files
                                          .updateMetadata(fileId, rev, metadata, tag)
                                          .index(mode)
                                          .attemptNarrow[FileRejection]
                                      )
                                    case None                           => reject
                                  }
                                )
                            },
                            parameters("storage".as[IdSegment].?, "tag".as[UserTag].?) { case (storage, tag) =>
                              concat(
                                // Link a file with id segment
                                entity(as[LinkFileRequest]) { linkRequest =>
                                  emit(
                                    Created,
                                    fileDescriptionFromRequest(linkRequest)
                                      .flatMap { description =>
                                        files
                                          .createLink(fileId, storage, description, linkRequest.path, tag)
                                          .index(mode)
                                      }
                                      .attemptNarrow[FileRejection]
                                  )
                                },
                                // Create a file with id segment
                                (extractRequestEntity & extractFileMetadata) { (entity, metadata) =>
                                  emit(
                                    Created,
                                    files
                                      .create(fileId, storage, entity, tag, metadata)
                                      .index(mode)
                                      .attemptNarrow[FileRejection]
                                  )
                                }
                              )
                            }
                          )
                        },
                        // Deprecate a file
                        (delete & parameter("rev".as[Int])) { rev =>
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
                    }
                  },
                  pathPrefix("tags") {
                    operationName(s"$prefixSegment/files/{org}/{project}/{id}/tags") {
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
                        (post & parameter("rev".as[Int]) & pathEndOrSingleSlash) { rev =>
                          authorizeFor(project, Write).apply {
                            entity(as[Tag]) { case Tag(tagRev, tag) =>
                              emit(
                                Created,
                                files.tag(fileId, tag, tagRev, rev).index(mode).attemptNarrow[FileRejection]
                              )
                            }
                          }
                        },
                        // Delete a tag
                        (tagLabel & delete & parameter("rev".as[Int]) & pathEndOrSingleSlash & authorizeFor(
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
                    }
                  },
                  (pathPrefix("undeprecate") & put & parameter("rev".as[Int])) { rev =>
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
    optionalHeaderValueByName("x-nxs-file-metadata").flatMap {
      case Some(metadata) =>
        val md = parser.parse(metadata).flatMap(_.as[FileCustomMetadata])
        md match {
          case Right(value) => provide(Some(value))
          case Left(err)    => reject(MalformedHeaderRejection("x-nxs-file-metadata", err.getMessage))
        }
      case None           => provide(Some(FileCustomMetadata.empty))
    }

  final case class LinkFileRequest(
      path: Path,
      filename: Option[String],
      mediaType: Option[ContentType],
      metadata: Option[FileCustomMetadata]
  )

  object LinkFileRequest {
    @nowarn("cat=unused")
    implicit private val config: Configuration             = Configuration.default
    implicit val linkFileDecoder: Decoder[LinkFileRequest] = deriveConfiguredDecoder[LinkFileRequest]

    def fileDescriptionFromRequest(f: LinkFileRequest): IO[FileDescription] =
      f.filename.orElse(f.path.lastSegment) match {
        case Some(value) => IO.pure(FileDescription(value, f.mediaType, f.metadata))
        case None        => IO.raiseError(InvalidFileLink)
      }
  }
}
