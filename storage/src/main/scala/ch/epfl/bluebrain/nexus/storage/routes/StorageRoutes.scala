package ch.epfl.bluebrain.nexus.storage.routes

import akka.http.scaladsl.model.MediaTypes._
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model.{HttpEntity, StatusCode, Uri}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import cats.data.NonEmptyList
import cats.effect.unsafe.implicits._
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.storage.File.{Digest, FileAttributes}
import ch.epfl.bluebrain.nexus.storage.config.AppConfig
import ch.epfl.bluebrain.nexus.storage.config.AppConfig.HttpConfig
import ch.epfl.bluebrain.nexus.storage.routes.StorageDirectives._
import ch.epfl.bluebrain.nexus.storage.routes.StorageRoutes.{CopyFilePayload, LinkFile}
import ch.epfl.bluebrain.nexus.storage.routes.StorageRoutes.LinkFile._
import ch.epfl.bluebrain.nexus.storage.routes.instances._
import ch.epfl.bluebrain.nexus.storage.{AkkaSource, Storages}
import io.circe.generic.semiauto._
import io.circe.{Decoder, DecodingFailure, Encoder}
import kamon.instrumentation.akka.http.TracingDirectives.operationName

class StorageRoutes()(implicit storages: Storages[AkkaSource], hc: HttpConfig) {

  def routes: Route =
    // Consume buckets/{name}/
    (encodeResponse & pathPrefix("buckets" / Segment)) { name =>
      concat(
        // Check bucket
        (head & pathEndOrSingleSlash) {
          operationName(s"/${hc.prefix}/buckets/{}") {
            bucketExists(name).apply { _ =>
              complete(OK)
            }
          }
        },
        // Consume files
        pathPrefix("files") {
          bucketExists(name).apply { implicit bucketExistsEvidence =>
            concat(
              extractPath(name) { path =>
                operationName(s"/${hc.prefix}/buckets/{}/files/{}") {
                  concat(
                    put {
                      pathNotExists(name, path).apply { implicit pathNotExistEvidence =>
                        // Upload file
                        fileUpload("file") { case (_, source) =>
                          complete(Created -> storages.createFile(name, path, source).unsafeToFuture())
                        }
                      }
                    },
                    put {
                      // Link file/dir
                      entity(as[LinkFile]) { case LinkFile(source) =>
                        validatePath(name, source) {
                          complete(storages.moveFile(name, source, path).runWithStatus(OK))
                        }
                      }
                    },
                    // Get file
                    get {
                      pathExists(name, path).apply { implicit pathExistsEvidence =>
                        storages.getFile(name, path) match {
                          case Right((source, Some(_))) => complete(HttpEntity(`application/octet-stream`, source))
                          case Right((source, None))    => complete(HttpEntity(`application/x-tar`, source))
                          case Left(err)                => complete(err)
                        }
                      }
                    }
                  )
                }
              },
              operationName(s"/${hc.prefix}/buckets/{}/files") {
                post {
                  // Copy files within protected directory
                  entity(as[CopyFilePayload]) { payload =>
                    val files = payload.files
                    pathsDoNotExist(name, files.map(_.destination)).apply { implicit pathNotExistEvidence =>
                      validatePaths(name, files.map(_.source)) {
                        complete(storages.copyFiles(name, files).runWithStatus(Created))
                      }
                    }
                  }
                }
              }
            )
          }
        },
        // Consume attributes
        (pathPrefix("attributes") & extractPath(name)) { path =>
          operationName(s"/${hc.prefix}/buckets/{}/attributes/{}") {
            bucketExists(name).apply { implicit bucketExistsEvidence =>
              // Get file attributes
              get {
                pathExists(name, path).apply { implicit pathExistsEvidence =>
                  val result = storages.getAttributes(name, path).map[(StatusCode, FileAttributes)] {
                    case attr @ FileAttributes(_, _, Digest.empty, _) => Accepted -> attr
                    case attr                                         => OK       -> attr
                  }
                  complete(result.unsafeToFuture())
                }
              }
            }
          }
        }
      )
    }
}

object StorageRoutes {

  /**
    * Link file request.
    *
    * @param source
    *   the location of the file/dir
    */
  final private[routes] case class LinkFile(source: Uri.Path)

  private[routes] object LinkFile {
    import ch.epfl.bluebrain.nexus.storage.{decUriPath, encUriPath}
    implicit val dec: Decoder[LinkFile] = deriveDecoder[LinkFile]
    implicit val enc: Encoder[LinkFile] = deriveEncoder[LinkFile]
  }

  final private[routes] case class CopyFilePayload(files: NonEmptyList[CopyFile])
  private[routes] object CopyFilePayload {
    implicit val dec: Decoder[CopyFilePayload] = Decoder.instance { cur =>
      cur
        .as[NonEmptyList[CopyFile]]
        .bimap(
          _ => DecodingFailure("No files provided for copy operation", Nil),
          files => CopyFilePayload(files)
        )
    }
  }

  final def apply(storages: Storages[AkkaSource])(implicit cfg: AppConfig): StorageRoutes = {
    implicit val s = storages
    new StorageRoutes()
  }
}
