package ch.epfl.bluebrain.nexus.storage.routes

import akka.http.scaladsl.model.MediaTypes._
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model.{HttpEntity, StatusCode, Uri}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import cats.effect.IO
import ch.epfl.bluebrain.nexus.storage.File.{Digest, FileAttributes}
import ch.epfl.bluebrain.nexus.storage.config.AppConfig
import ch.epfl.bluebrain.nexus.storage.config.AppConfig.HttpConfig
import ch.epfl.bluebrain.nexus.storage.routes.StorageDirectives._
import ch.epfl.bluebrain.nexus.storage.routes.StorageRoutes.LinkFile
import ch.epfl.bluebrain.nexus.storage.routes.StorageRoutes.LinkFile._
import ch.epfl.bluebrain.nexus.storage.routes.instances._
import ch.epfl.bluebrain.nexus.storage.{AkkaSource, Storages}
import io.circe.generic.semiauto._
import io.circe.{Decoder, Encoder}
import kamon.instrumentation.akka.http.TracingDirectives.operationName

class StorageRoutes()(implicit storages: Storages[IO, AkkaSource], hc: HttpConfig) {

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
        (pathPrefix("files") & extractPath(name)) { path =>
          operationName(s"/${hc.prefix}/buckets/{}/files/{}") {
            bucketExists(name).apply { implicit bucketExistsEvidence =>
              concat(
                put {
                  pathNotExists(name, path).apply { implicit pathNotExistEvidence =>
                    concat(
                      // Link file/dir
                      (parameter("keepSource".as[Boolean].?) & entity(as[LinkFile])) {
                        case (maybeKeepSource, LinkFile(source)) =>
                          val keepSource = maybeKeepSource.getOrElse(false)
                          validatePath(name, source) {
                            if (keepSource)
                              complete(storages.copyFile(name, source, path).runWithStatus(Created))
                            else complete(storages.moveFile(name, source, path).runWithStatus(OK))
                          }
                      },
                      // Upload file
                      fileUpload("file") { case (_, source) =>
                        complete(Created -> storages.createFile(name, path, source).unsafeToFuture())
                      }
                    )
                  }
                },
//                put {
//                  pathNotExists(name, path).apply { implicit pathNotExistEvidence =>
//                      // Link file/dir
//                      (parameter("keepSource".as[Boolean].?) & entity(as[LinkFile])) { case (maybeKeepSource, LinkFile(source)) =>
//                        val keepSource = maybeKeepSource.getOrElse(false)
//                        validatePath(name, source) {
//                          if (keepSource) complete(storages.copyFile(name, source, path).runWithStatus(Created))
//                          else complete(storages.moveFile(name, source, path).runWithStatus(OK))
//                        }
//                      }
//                  }
//                },
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
    import ch.epfl.bluebrain.nexus.storage._
    implicit val linkFileDec: Decoder[LinkFile] = deriveDecoder[LinkFile]
    implicit val linkFileEnc: Encoder[LinkFile] = deriveEncoder[LinkFile]
  }

  final def apply(storages: Storages[IO, AkkaSource])(implicit cfg: AppConfig): StorageRoutes = {
    implicit val s = storages
    new StorageRoutes()
  }
}
