package ch.epfl.bluebrain.nexus.kg.routes

import akka.actor.ActorSystem
import akka.http.scaladsl.model.StatusCodes.{Created, OK}
import akka.http.scaladsl.model.headers.{Accept, RawHeader}
import akka.http.scaladsl.model.HttpEntity
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import cats.data.EitherT
import ch.epfl.bluebrain.nexus.admin.client.types.Project
import ch.epfl.bluebrain.nexus.iam.acls.Acls
import ch.epfl.bluebrain.nexus.iam.realms.Realms
import ch.epfl.bluebrain.nexus.iam.types.Caller
import ch.epfl.bluebrain.nexus.iam.types.Identity.Subject
import ch.epfl.bluebrain.nexus.kg.KgError.{InvalidOutputFormat, UnacceptedResponseContentType}
import ch.epfl.bluebrain.nexus.kg.cache._
import ch.epfl.bluebrain.nexus.kg.config.Schemas._
import ch.epfl.bluebrain.nexus.kg.directives.PathDirectives._
import ch.epfl.bluebrain.nexus.kg.directives.ProjectDirectives._
import ch.epfl.bluebrain.nexus.kg.directives.QueryDirectives._
import ch.epfl.bluebrain.nexus.kg.marshallers.instances._
import ch.epfl.bluebrain.nexus.kg.resources._
import ch.epfl.bluebrain.nexus.kg.resources.file.File._
import ch.epfl.bluebrain.nexus.kg.resources.syntax._
import ch.epfl.bluebrain.nexus.kg.routes.OutputFormat._
import ch.epfl.bluebrain.nexus.kg.search.QueryResultEncoder._
import ch.epfl.bluebrain.nexus.kg.storage.{AkkaSource, Storage}
import ch.epfl.bluebrain.nexus.rdf.Iri.AbsoluteIri
import ch.epfl.bluebrain.nexus.rdf.Iri.Path._
import ch.epfl.bluebrain.nexus.service.config.ServiceConfig
import ch.epfl.bluebrain.nexus.service.config.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.service.directives.AuthDirectives
import com.google.common.base.Charsets
import com.google.common.io.BaseEncoding
import io.circe.Json
import kamon.Kamon
import kamon.instrumentation.akka.http.TracingDirectives.operationName
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global

import scala.concurrent.Future

class FileRoutes private[routes] (
    files: Files[Task],
    resources: Resources[Task],
    tags: Tags[Task],
    acls: Acls[Task],
    realms: Realms[Task]
)(implicit
    system: ActorSystem,
    caller: Caller,
    project: Project,
    viewCache: ViewCache[Task],
    storageCache: StorageCache[Task],
    indexers: Clients[Task],
    config: ServiceConfig
) extends AuthDirectives(acls, realms) {

  private val projectPath               = project.organizationLabel / project.label
  implicit private val subject: Subject = caller.subject

  import indexers._

  /**
    * Routes for files. Those routes should get triggered after the following segments have been consumed:
    * <ul>
    *   <li> {prefix}/files/{org}/{project}. E.g.: v1/files/myorg/myproject </li>
    *   <li> {prefix}/resources/{org}/{project}/{fileSchemaUri}. E.g.: v1/resources/myorg/myproject/file </li>
    * </ul>
    */
  def routes: Route =
    concat(
      // Create file when id is not provided in the Uri (POST)
      (post & projectNotDeprecated & pathEndOrSingleSlash & storage) { storage =>
        concat(
          // Uploading a file from the client
          (withSizeLimit(storage.maxFileSize) & fileUpload("file")) {
            case (metadata, byteSource) =>
              operationName(s"/${config.http.prefix}/files/{org}/{project}") {
                Kamon.currentSpan().tag("file.operation", "upload").tag("resource.operation", "create")
                authorizeFor(projectPath, storage.writePermission)(caller) {
                  val description = FileDescription(metadata.fileName, metadata.contentType)
                  val created     = files.create(storage, description, byteSource)
                  complete(created.value.runWithStatus(Created))
                }
              }
          },
          // Linking a file from the storage service
          entity(as[Json]) { source =>
            operationName(s"/${config.http.prefix}/files/{org}/{project}") {
              Kamon.currentSpan().tag("file.operation", "link").tag("resource.operation", "create")
              authorizeFor(projectPath, storage.writePermission)(caller) {
                val created = files.createLink(storage, source)
                complete(created.value.runWithStatus(Created))
              }
            }
          }
        )
      },
      // List files
      (get & paginated & searchParams(fixedSchema = fileSchemaUri) & pathEndOrSingleSlash) { (page, params) =>
        extractUri { implicit uri =>
          operationName(s"/${config.http.prefix}/files/{org}/{project}") {
            authorizeFor(projectPath, read)(caller) {
              val listed = viewCache.getDefaultElasticSearch(project.ref).flatMap(files.list(_, params, page))
              complete(listed.runWithStatus(OK))
            }
          }
        }
      },
      // Consume the file id segment
      pathPrefix(IdSegment) { id =>
        routes(id)
      }
    )

  /**
    * Routes for files when the id is specified.
    * Those routes should get triggered after the following segments have been consumed:
    * <ul>
    *   <li> {prefix}/files/{org}/{project}/{id}. E.g.: v1/files/myorg/myproject/myfile </li>
    *   <li> {prefix}/resources/{org}/{project}/{fileSchemaUri}/{id}. E.g.: v1/resources/myorg/myproject/file/myfile</li>
    * </ul>
    */
  def routes(id: AbsoluteIri): Route =
    concat(
      // Create or update a file (depending on rev query parameter)
      (put & pathEndOrSingleSlash & storage) { storage =>
        operationName(s"/${config.http.prefix}/files/{org}/{project}/{id}") {
          val resId = Id(project.ref, id)
          (authorizeFor(projectPath, storage.writePermission) & projectNotDeprecated) {
            // Uploading a file from the client
            concat(
              (withSizeLimit(storage.maxFileSize) & fileUpload("file")) {
                case (metadata, byteSource) =>
                  val description = FileDescription(metadata.fileName, metadata.contentType)
                  parameter("rev".as[Long].?) {
                    case None      =>
                      Kamon.currentSpan().tag("file.operation", "upload").tag("resource.operation", "create")
                      complete(files.create(resId, storage, description, byteSource).value.runWithStatus(Created))
                    case Some(rev) =>
                      Kamon.currentSpan().tag("file.operation", "upload").tag("resource.operation", "update")
                      complete(files.update(resId, storage, rev, description, byteSource).value.runWithStatus(OK))
                  }
              },
              // Linking a file from the storage service
              entity(as[Json]) {
                source =>
                  parameter("rev".as[Long].?) {
                    case None      =>
                      Kamon.currentSpan().tag("file.operation", "link").tag("resource.operation", "create")
                      complete(files.createLink(resId, storage, source).value.runWithStatus(Created))
                    case Some(rev) =>
                      Kamon.currentSpan().tag("file.operation", "link").tag("resource.operation", "update")
                      complete(files.updateLink(resId, storage, rev, source).value.runWithStatus(OK))
                  }
              }
            )
          }
        }
      },
      // Updating file attributes manually
      (patch & pathEndOrSingleSlash & storage & parameter("rev".as[Long])) { (storage, rev) =>
        operationName(s"/${config.http.prefix}/files/{org}/{project}/{id}") {
          (authorizeFor(projectPath, storage.writePermission) & projectNotDeprecated) {
            entity(as[Json]) { source =>
              complete(files.updateFileAttr(Id(project.ref, id), storage, rev, source).value.runWithStatus(OK))
            }
          }
        }
      },
      // Deprecate file
      (delete & parameter("rev".as[Long]) & pathEndOrSingleSlash) { rev =>
        operationName(s"/${config.http.prefix}/files/{org}/{project}/{id}") {
          (authorizeFor(projectPath, write) & projectNotDeprecated) {
            complete(files.deprecate(Id(project.ref, id), rev).value.runWithStatus(OK))
          }
        }
      },
      // Fetch file
      (get & outputFormat(strict = true, Binary) & pathEndOrSingleSlash) {
        case Binary                        => getFile(id)
        case format: NonBinaryOutputFormat => getResource(id)(format)
        case other                         => failWith(InvalidOutputFormat(other.toString))
      },
      // Fetch file source
      (get & pathPrefix("source") & pathEndOrSingleSlash) {
        operationName(s"/${config.http.prefix}/files/{org}/{project}/{id}/source") {
          authorizeFor(projectPath, read)(caller) {
            concat(
              (parameter("rev".as[Long]) & noParameter("tag")) { rev =>
                complete(resources.fetchSource(Id(project.ref, id), rev, fileRef).value.runWithStatus(OK))
              },
              (parameter("tag") & noParameter("rev")) { tag =>
                complete(resources.fetchSource(Id(project.ref, id), tag, fileRef).value.runWithStatus(OK))
              },
              (noParameter("tag") & noParameter("rev")) {
                complete(resources.fetchSource(Id(project.ref, id), fileRef).value.runWithStatus(OK))
              }
            )
          }
        }
      },
      // Incoming links
      (get & pathPrefix("incoming") & pathEndOrSingleSlash) {
        fromPaginated.apply {
          implicit page =>
            extractUri {
              implicit uri =>
                operationName(s"/${config.http.prefix}/files/{org}/{project}/{id}/incoming") {
                  authorizeFor(projectPath, read)(caller) {
                    val listed = for {
                      view     <- viewCache.getDefaultSparql(project.ref).toNotFound(nxv.defaultSparqlIndex.value)
                      _        <- resources.fetchSource(Id(project.ref, id), fileRef)
                      incoming <- EitherT.right[Rejection](files.listIncoming(id, view, page))
                    } yield incoming
                    complete(listed.value.runWithStatus(OK))
                  }
                }
            }
        }
      },
      // Outgoing links
      (get & pathPrefix("outgoing") & parameter("includeExternalLinks".as[Boolean] ? true) & pathEndOrSingleSlash) {
        links =>
          fromPaginated.apply {
            implicit page =>
              extractUri {
                implicit uri =>
                  operationName(s"/${config.http.prefix}/files/{org}/{project}/{id}/outgoing") {
                    authorizeFor(projectPath, read)(caller) {
                      val listed = for {
                        view     <- viewCache.getDefaultSparql(project.ref).toNotFound(nxv.defaultSparqlIndex.value)
                        _        <- resources.fetchSource(Id(project.ref, id), fileRef)
                        outgoing <- EitherT.right[Rejection](files.listOutgoing(id, view, page, links))
                      } yield outgoing
                      complete(listed.value.runWithStatus(OK))
                    }
                  }
              }
          }
      },
      new TagRoutes("files", tags, acls, realms, fileRef, write).routes(id)
    )

  private def getResource(id: AbsoluteIri)(implicit format: NonBinaryOutputFormat) =
    operationName(s"/${config.http.prefix}/files/{org}/{project}/{id}") {
      Kamon.currentSpan().tag("file.operation", "read")
      authorizeFor(projectPath, read)(caller) {
        concat(
          (parameter("rev".as[Long]) & noParameter("tag")) { rev =>
            completeWithFormat(resources.fetch(Id(project.ref, id), rev, fileRef).value.runWithStatus(OK))
          },
          (parameter("tag") & noParameter("rev")) { tag =>
            completeWithFormat(resources.fetch(Id(project.ref, id), tag, fileRef).value.runWithStatus(OK))
          },
          (noParameter("tag") & noParameter("rev")) {
            completeWithFormat(resources.fetch(Id(project.ref, id), fileRef).value.runWithStatus(OK))
          }
        )
      }
    }

  private def getFile(id: AbsoluteIri): Route =
    operationName(s"/${config.http.prefix}/files/{org}/{project}/{id}") {
      Kamon.currentSpan().tag("file.operation", "download")
      // permission checks are in completeFile
      concat(
        (parameter("rev".as[Long]) & noParameter("tag")) { rev =>
          completeFile(files.fetch(Id(project.ref, id), rev).value.runToFuture)
        },
        (parameter("tag") & noParameter("rev")) { tag =>
          completeFile(files.fetch(Id(project.ref, id), tag).value.runToFuture)
        },
        (noParameter("tag") & noParameter("rev")) {
          completeFile(files.fetch(Id(project.ref, id)).value.runToFuture)
        }
      )
    }

  // From the RFC 2047: "=?" charset "?" encoding "?" encoded-text "?="
  private def attachmentString(filename: String): String = {
    val encodedFilename = BaseEncoding.base64().encode(filename.getBytes(Charsets.UTF_8))
    s"=?UTF-8?B?$encodedFilename?="
  }

  private def completeFile(f: Future[Either[Rejection, (Storage, FileAttributes, AkkaSource)]]): Route =
    onSuccess(f) {
      case Right((storage, info, source)) =>
        authorizeFor(projectPath, storage.readPermission)(caller) {
          val encodedFilename = attachmentString(info.filename)
          (respondWithHeaders(
            RawHeader("Content-Disposition", s"""attachment; filename="$encodedFilename"""")
          ) & encodeResponse) {
            headerValueByType[Accept](()) { accept =>
              if (accept.mediaRanges.exists(_.matches(info.mediaType.mediaType)))
                complete(HttpEntity(info.mediaType, source))
              else
                failWith(
                  UnacceptedResponseContentType(
                    s"File Media Type '${info.mediaType}' does not match the Accept header value '${accept.mediaRanges
                      .mkString(", ")}'"
                  )
                )
            }
          }
        }
      case Left(err)                      => complete(err)
    }
}
