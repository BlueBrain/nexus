package ch.epfl.bluebrain.nexus.delta.plugins.storage.files.routes

import akka.http.scaladsl.model.StatusCodes.Created
import akka.http.scaladsl.model.Uri.Path
import akka.http.scaladsl.model.{ContentType, StatusCodes}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server._
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileRejection
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileRejection._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.routes.FilesRoutes._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.{FileResource, Files}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StoragesConfig.StorageTypeConfig
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.StorageFileRejection.{FetchFileRejection, SaveFileRejection}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.permissions
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.routes.StoragesRoutes._
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.Permissions.events
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.circe.CirceUnmarshalling
import ch.epfl.bluebrain.nexus.delta.sdk.directives.{AuthDirectives, FileResponse}
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaDirectives._
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.HttpResponseFields
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.RdfRejectionHandler._
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.RdfRejectionHandler.all._
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.routes.{Tag, Tags}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, IdSegment, TagLabel}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.utils.HeadersUtils
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
  * @param identities    the identity module
  * @param acls          the acls module
  * @param organizations the organizations module
  * @param projects      the projects module
  * @param files         the files module
  */
final class FilesRoutes(
    identities: Identities,
    acls: Acls,
    organizations: Organizations,
    projects: Projects,
    files: Files
)(implicit
    baseUri: BaseUri,
    storageConfig: StorageTypeConfig,
    s: Scheduler,
    cr: RemoteContextResolution,
    ordering: JsonKeyOrdering
) extends AuthDirectives(identities, acls)
    with CirceUnmarshalling {

  import baseUri.prefixSegment

  def routes: Route =
    baseUriPrefix(baseUri.prefix) {
      extractCaller { implicit caller =>
        pathPrefix("files") {
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
                  authorizeFor(AclAddress.Organization(org), events.read).apply {
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
                      authorizeFor(AclAddress.Project(ref), events.read).apply {
                        lastEventId { offset =>
                          emit(files.events(ref, offset))
                        }
                      }
                    }
                  }
                },
                (post & pathEndOrSingleSlash & noParameter("rev") & parameter("storage".as[IdSegment].?)) { storage =>
                  concat(
                    // Link a file without id segment
                    entity(as[LinkFile]) { case LinkFile(filename, mediaType, path) =>
                      emit(Created, files.createLink(storage, ref, filename, mediaType, path))
                    },
                    // Create a file without id segment
                    extractRequestEntity { entity =>
                      operationName(s"$prefixSegment/files/{org}/{project}") {
                        emit(Created, files.create(storage, ref, entity))
                      }
                    }
                  )
                },
                idSegment { id =>
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
                                    emit(Created, files.createLink(id, storage, ref, filename, mediaType, path))
                                  },
                                  // Create a file with id segment
                                  extractRequestEntity { entity =>
                                    emit(Created, files.create(id, storage, ref, entity))
                                  }
                                )
                              case (Some(rev), storage) =>
                                concat(
                                  // Update a Link
                                  entity(as[LinkFile]) { case LinkFile(filename, mediaType, path) =>
                                    emit(files.updateLink(id, storage, ref, filename, mediaType, path, rev))
                                  },
                                  // Update a file
                                  extractRequestEntity { entity =>
                                    emit(files.update(id, storage, ref, rev, entity))
                                  }
                                )
                            }
                          },
                          // Deprecate a file
                          (delete & parameter("rev".as[Long])) { rev =>
                            authorizeFor(AclAddress.Project(ref), permissions.write).apply {
                              emit(files.deprecate(id, ref, rev))
                            }
                          },
                          // Fetch a file
                          get {
                            fetch(id, ref)
                          }
                        )
                      }
                    },
                    (pathPrefix("tags") & pathEndOrSingleSlash) {
                      operationName(s"$prefixSegment/files/{org}/{project}/{id}/tags") {
                        concat(
                          // Fetch a file tags
                          get {
                            fetchMap(id, ref, resource => Tags(resource.value.tags))
                          },
                          // Tag a file
                          (post & parameter("rev".as[Long])) { rev =>
                            authorizeFor(AclAddress.Project(ref), permissions.write).apply {
                              entity(as[Tag]) { case Tag(tagRev, tag) =>
                                emit(Created, files.tag(id, ref, tag, tagRev, rev))
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

  private def fetchMap[A: JsonLdEncoder](
      id: IdSegment,
      ref: ProjectRef,
      f: FileResource => A
  )(implicit caller: Caller): Route =
    parameters("rev".as[Long].?, "tag".as[TagLabel].?) {
      case (Some(_), Some(_)) => emit(simultaneousTagAndRevRejection)
      case (revOpt, tagOpt)   => emit(fetchMetadata(id, ref, revOpt, tagOpt).map(f))
    }

  def fetch(id: IdSegment, ref: ProjectRef)(implicit caller: Caller): Route =
    extractRequest { req =>
      parameters("rev".as[Long].?, "tag".as[TagLabel].?) {
        case (Some(_), Some(_)) =>
          emit(simultaneousTagAndRevRejection)
        case (revOpt, tagOpt)   =>
          val ioResult = fetchContent(id, ref, revOpt, tagOpt).flatMap {
            case r @ FileResponse(_, ct, _) if HeadersUtils.matches(req.headers, ct.mediaType) => IO.pure(Right(r))
            case _                                                                             => fetchMetadata(id, ref, revOpt, tagOpt).map(Left.apply)
          }
          onSuccess(ioResult.attempt.runToFuture) {
            case Left(rej)                 => emit(rej)
            case Right(Right(fileContent)) => emit(fileContent)
            case Right(Left(fileMetadata)) => emit(fileMetadata)
          }
      }
    }

  private def fetchContent(
      id: IdSegment,
      ref: ProjectRef,
      revOpt: Option[Long],
      tagOpt: Option[TagLabel]
  )(implicit caller: Caller): IO[FileRejection, FileResponse] =
    (revOpt, tagOpt) match {
      case (Some(rev), _) => files.fetchContentAt(id, ref, rev)
      case (_, Some(tag)) => files.fetchContentBy(id, ref, tag)
      case _              => files.fetchContent(id, ref)
    }

  private def fetchMetadata(
      id: IdSegment,
      ref: ProjectRef,
      revOpt: Option[Long],
      tagOpt: Option[TagLabel]
  )(implicit caller: Caller): IO[FileRejection, FileResource] =
    files.authorizeFor(ref, permissions.read) >>
      ((revOpt, tagOpt) match {
        case (Some(rev), _) => files.fetchAt(id, ref, rev)
        case (_, Some(tag)) => files.fetchBy(id, ref, tag)
        case _              => files.fetch(id, ref)
      })
}

object FilesRoutes {

  /**
    * @return the [[Route]] for files
    */
  def apply(
      config: StorageTypeConfig,
      identities: Identities,
      acls: Acls,
      organizations: Organizations,
      projects: Projects,
      files: Files
  )(implicit
      baseUri: BaseUri,
      s: Scheduler,
      cr: RemoteContextResolution,
      ordering: JsonKeyOrdering
  ): Route = {
    implicit val storageTypeConfig: StorageTypeConfig = config
    new FilesRoutes(identities, acls, organizations, projects, files).routes
  }

  final case class LinkFile(filename: Option[String], mediaType: Option[ContentType], path: Path)
  object LinkFile {
    import ch.epfl.bluebrain.nexus.delta.plugins.storage.instances._
    import ch.epfl.bluebrain.nexus.delta.rdf.instances._
    @nowarn("cat=unused")
    implicit private val config: Configuration      = Configuration.default.withStrictDecoding
    implicit val linkFileDecoder: Decoder[LinkFile] = deriveConfiguredDecoder[LinkFile]
  }

  implicit private[routes] val responseFieldsFiles: HttpResponseFields[FileRejection] =
    HttpResponseFields.fromStatusAndHeaders {
      case RevisionNotFound(_, _)                                      => (StatusCodes.NotFound, Seq.empty)
      case TagNotFound(_)                                              => (StatusCodes.NotFound, Seq.empty)
      case FileNotFound(_, _)                                          => (StatusCodes.NotFound, Seq.empty)
      case FileAlreadyExists(_, _)                                     => (StatusCodes.Conflict, Seq.empty)
      case IncorrectRev(_, _)                                          => (StatusCodes.Conflict, Seq.empty)
      case WrappedAkkaRejection(rej)                                   => (rej.status, rej.headers)
      case WrappedStorageRejection(rej)                                => (rej.status, rej.headers)
      case WrappedProjectRejection(rej)                                => (rej.status, rej.headers)
      case WrappedOrganizationRejection(rej)                           => (rej.status, rej.headers)
      case FetchRejection(_, _, FetchFileRejection.FileNotFound(_))    => (StatusCodes.NotFound, Seq.empty)
      case FetchRejection(_, _, _)                                     => (StatusCodes.InternalServerError, Seq.empty)
      case SaveRejection(_, _, SaveFileRejection.FileAlreadyExists(_)) => (StatusCodes.Conflict, Seq.empty)
      case SaveRejection(_, _, _)                                      => (StatusCodes.InternalServerError, Seq.empty)
      case UnexpectedInitialState(_, _)                                => (StatusCodes.InternalServerError, Seq.empty)
      case AuthorizationFailed                                         => (StatusCodes.Forbidden, Seq.empty)
      case _                                                           => (StatusCodes.BadRequest, Seq.empty)
    }
}
