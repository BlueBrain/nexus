package ch.epfl.bluebrain.nexus.storage.routes

import akka.http.scaladsl.model.MediaTypes._
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model.{HttpEntity, StatusCode, Uri}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import cats.data.NonEmptyList
import cats.effect.unsafe.implicits._
import ch.epfl.bluebrain.nexus.storage.File.{Digest, FileAttributes}
import ch.epfl.bluebrain.nexus.storage.config.AppConfig
import ch.epfl.bluebrain.nexus.storage.config.AppConfig.HttpConfig
import ch.epfl.bluebrain.nexus.storage.routes.StorageDirectives._
import ch.epfl.bluebrain.nexus.storage.routes.StorageRoutes.LinkFile
import ch.epfl.bluebrain.nexus.storage.routes.StorageRoutes.LinkFile._
import ch.epfl.bluebrain.nexus.storage.routes.instances._
import ch.epfl.bluebrain.nexus.storage.{AkkaSource, CopyFile, Storages}
import io.circe.generic.semiauto._
import io.circe.{Decoder, Encoder}
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
          concat(
            extractPath(name) { path =>
              operationName(s"/${hc.prefix}/buckets/{}/files/{}") {
                bucketExists(name).apply { implicit bucketExistsEvidence =>
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
              }
            },
            post {
              println(s"DTB did we get here")
              bucketExists(name).apply { implicit bucketExistsEvidence =>
                println(s"DTB did we get here 2")
                // Copy file to/from protected directory
                entity(as[NonEmptyList[CopyFile]]) { files =>
                  println(s"DTB did we get here 3")
                  pathsDoNotExist(name, files.map(_.destination)).apply { implicit pathNotExistEvidence =>
                    println(s"DTB did we get here 4")
                    validatePaths(name, files.map(_.source)) {
                      println(s"DTB did we get here 5")
                      complete(storages.copyFile2(name, files).runWithStatus(Created))
                    }
                  }
                }
              }
            }
          )
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
    implicit val dec: Decoder[LinkFile] = deriveDecoder[LinkFile]
    implicit val enc: Encoder[LinkFile] = deriveEncoder[LinkFile]
  }

  final def apply(storages: Storages[AkkaSource])(implicit cfg: AppConfig): StorageRoutes = {
    implicit val s = storages
    new StorageRoutes()
  }
}
