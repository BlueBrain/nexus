package ch.epfl.bluebrain.nexus.delta.plugins.storage.files.routes

import akka.http.scaladsl.model.StatusCodes.Created
import akka.http.scaladsl.model.Uri.Path
import akka.http.scaladsl.model.headers.Accept
import akka.http.scaladsl.model.{ContentType, MediaRange}
import akka.http.scaladsl.server._
import cats.data.EitherT
import cats.effect.IO
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileRejection._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.{CopyFileDestination, File, FileId, FileRejection}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.permissions.{read => Read, write => Write}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.routes.FilesRoutes._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.{schemas, FileResource, Files}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StoragesConfig.StorageTypeConfig
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
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import io.circe.Decoder
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredDecoder
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
    storageConfig: StorageTypeConfig,
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
          resolveProjectRef.apply { projectRef =>
            implicit class IndexOps(io: IO[FileResource]) {
              def index(m: IndexingMode): IO[FileResource] = io.flatTap(self.index(projectRef, _, m))
            }

            concat(
              (post & pathEndOrSingleSlash & noParameter("rev") & parameter(
                "storage".as[IdSegment].?
              ) & indexingMode & tagParam) { (storage, mode, tag) =>
                operationName(s"$prefixSegment/files/{org}/{project}") {
                  concat(
                    // Link a file without id segment
                    entity(as[LinkFile]) { case LinkFile(filename, mediaType, path) =>
                      emit(
                        Created,
                        files
                          .createLink(storage, projectRef, filename, mediaType, path, tag)
                          .index(mode)
                          .attemptNarrow[FileRejection]
                      )
                    },
                    // Create a file by copying from another project, without id segment
                    entity(as[CopyFilePayload]) { c: CopyFilePayload =>
                      val copyTo = CopyFileDestination(projectRef, None, storage, tag, c.destFilename)

                      emit(Created, copyFile(projectRef, mode, c, copyTo))
                    },
                    // Create a file without id segment
                    extractRequestEntity { entity =>
                      emit(
                        Created,
                        files.create(storage, projectRef, entity, tag).index(mode).attemptNarrow[FileRejection]
                      )
                    }
                  )
                }
              },
              (idSegment & indexingMode) { (id, mode) =>
                val fileId = FileId(id, projectRef)
                concat(
                  pathEndOrSingleSlash {
                    operationName(s"$prefixSegment/files/{org}/{project}/{id}") {
                      concat(
                        (put & pathEndOrSingleSlash) {
                          concat(
                            // Create a file by copying from another project
                            parameters("storage".as[IdSegment].?, "tag".as[UserTag].?) { case (destStorage, destTag) =>
                              entity(as[CopyFilePayload]) { c: CopyFilePayload =>
                                val copyTo =
                                  CopyFileDestination(projectRef, Some(id), destStorage, destTag, c.destFilename)

                                emit(Created, copyFile(projectRef, mode, c, copyTo))
                              }
                            },
                            parameters("rev".as[Int], "storage".as[IdSegment].?, "tag".as[UserTag].?) {
                              case (rev, storage, tag) =>
                                concat(
                                  // Update a Link
                                  entity(as[LinkFile]) { case LinkFile(filename, mediaType, path) =>
                                    emit(
                                      files
                                        .updateLink(fileId, storage, filename, mediaType, path, rev, tag)
                                        .index(mode)
                                        .attemptNarrow[FileRejection]
                                    )
                                  },
                                  // Update a file
                                  extractRequestEntity { entity =>
                                    emit(
                                      files
                                        .update(fileId, storage, rev, entity, tag)
                                        .index(mode)
                                        .attemptNarrow[FileRejection]
                                    )
                                  }
                                )
                            },
                            parameters("storage".as[IdSegment].?, "tag".as[UserTag].?) { case (storage, tag) =>
                              concat(
                                // Link a file with id segment
                                entity(as[LinkFile]) { case LinkFile(filename, mediaType, path) =>
                                  emit(
                                    Created,
                                    files
                                      .createLink(fileId, storage, filename, mediaType, path, tag)
                                      .index(mode)
                                      .attemptNarrow[FileRejection]
                                  )
                                },
                                // Create a file with id segment
                                extractRequestEntity { entity =>
                                  emit(
                                    Created,
                                    files
                                      .create(fileId, storage, entity, tag)
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
                          authorizeFor(projectRef, Write).apply {
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
                          emitOrFusionRedirect(projectRef, id, fetch(FileId(id, projectRef)))
                        }
                      )
                    }
                  },
                  (pathPrefix("tags")) {
                    operationName(s"$prefixSegment/files/{org}/{project}/{id}/tags") {
                      concat(
                        // Fetch a file tags
                        (get & idSegmentRef(id) & pathEndOrSingleSlash & authorizeFor(projectRef, Read)) { id =>
                          emit(
                            fetchMetadata(FileId(id, projectRef))
                              .map(_.value.tags)
                              .attemptNarrow[FileRejection]
                              .rejectOn[FileNotFound]
                          )
                        },
                        // Tag a file
                        (post & parameter("rev".as[Int]) & pathEndOrSingleSlash) { rev =>
                          authorizeFor(projectRef, Write).apply {
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
                          projectRef,
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
                    authorizeFor(projectRef, Write).apply {
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

  private def copyFile(projectRef: ProjectRef, mode: IndexingMode, c: CopyFilePayload, copyTo: CopyFileDestination)(
      implicit caller: Caller
  ): IO[Either[FileRejection, FileResource]] =
    (for {
      _            <- EitherT.right(aclCheck.authorizeForOr(c.sourceProj, Read)(AuthorizationFailed(c.sourceProj.project, Read)))
      sourceFileId <- EitherT.fromEither[IO](c.toSourceFileId)
      result       <- EitherT(files.copyTo(sourceFileId, copyTo).attemptNarrow[FileRejection])
      _            <- EitherT.right[FileRejection](index(projectRef, result, mode))
    } yield result).value

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
      config: StorageTypeConfig,
      identities: Identities,
      aclCheck: AclCheck,
      files: Files,
      schemeDirectives: DeltaSchemeDirectives,
      index: IndexingAction.Execute[File]
  )(implicit
      baseUri: BaseUri,
      cr: RemoteContextResolution,
      ordering: JsonKeyOrdering,
      fusionConfig: FusionConfig
  ): Route = {
    implicit val storageTypeConfig: StorageTypeConfig = config
    new FilesRoutes(identities, aclCheck, files, schemeDirectives, index).routes
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
