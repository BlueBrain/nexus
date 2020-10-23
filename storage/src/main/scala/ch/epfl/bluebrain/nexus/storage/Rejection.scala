package ch.epfl.bluebrain.nexus.storage

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.Uri.Path
import akka.http.scaladsl.server.{Rejection => AkkaRejection}
import ch.epfl.bluebrain.nexus.storage.routes.StatusFrom
import scala.annotation.nowarn
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredEncoder
import io.circe.{Encoder, Json}

/**
  * Enumeration of resource rejection types.
  *
  * @param msg a descriptive message of the rejection
  */
sealed abstract class Rejection(val msg: String) extends AkkaRejection with Product with Serializable

object Rejection {

  /**
    * Signals an attempt to interact with a bucket that doesn't exist.
    *
    * @param name the storage bucket name
    */
  final case class BucketNotFound(name: String) extends Rejection(s"The provided bucket '$name' does not exist.")

  /**
    * Signals an attempt to override a path that already exists.
    *
    * @param name the storage bucket name
    * @param path the relative path to the file
    */
  final case class PathAlreadyExists(name: String, path: Path)
      extends Rejection(
        s"The provided location inside the bucket '$name' with the relative path '$path' already exists."
      )

  /**
    * Signals an attempt to interact with a path that doesn't exist.
    *
    * @param name the storage bucket name
    * @param path the relative path to the file
    */
  final case class PathNotFound(name: String, path: Path)
      extends Rejection(
        s"The provided location inside the bucket '$name' with the relative path '$path' does not exist."
      )

  /**
    * Signals that the location contains symbolic or hard links.
    *
    * @param name the storage bucket name
    * @param path the relative path to the file
    */
  final case class PathContainsLinks(name: String, path: Path)
      extends Rejection(
        s"The provided location inside the bucket '$name' with the relative path '$path' contains links. Please remove them in order to proceed with this call."
      )

  /**
    * Signals a missing.
    * @param name the storage bucket name
    */
  final case class EmptyFilename(name: String)

  implicit def statusCodeFrom: StatusFrom[Rejection] =
    StatusFrom {
      case _: PathContainsLinks => StatusCodes.BadRequest
      case _: PathAlreadyExists => StatusCodes.Conflict
      case _: BucketNotFound    => StatusCodes.NotFound
      case _: PathNotFound      => StatusCodes.NotFound
    }

  @nowarn("cat=unused")
  implicit val rejectionEncoder: Encoder[Rejection] = {
    implicit val rejectionConfig: Configuration = Configuration.default.withDiscriminator("@type")
    val enc                                     = deriveConfiguredEncoder[Rejection].mapJson(jsonError)
    Encoder.instance(r => enc(r) deepMerge Json.obj("reason" -> Json.fromString(r.msg)))
  }
}
