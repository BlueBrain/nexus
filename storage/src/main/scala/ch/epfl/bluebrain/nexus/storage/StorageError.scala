package ch.epfl.bluebrain.nexus.storage

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.Uri.Path
import ch.epfl.bluebrain.nexus.storage.routes.StatusFrom
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredEncoder
import io.circe.{Encoder, Json}

import scala.annotation.nowarn

/**
  * Enumeration of runtime errors.
  *
  * @param msg a description of the error
  */

sealed abstract class StorageError(val msg: String) extends Exception with Product with Serializable {
  override def fillInStackTrace(): StorageError = this
  override def getMessage: String               = msg
}

object StorageError {

  /**
    * Generic wrapper for kg errors that should not be exposed to clients.
    *
    * @param reason the underlying error reason
    */
  final case class InternalError(reason: String) extends StorageError(reason)

  /**
    * Signals that the provided authentication is not valid.
    */
  final case object AuthenticationFailed extends StorageError("The supplied authentication is invalid.")

  /**
    * Signals that the caller doesn't have access to the selected resource.
    */
  final case object AuthorizationFailed
      extends StorageError("The supplied authentication is not authorized to access this resource.")

  /**
    * Signals the inability to connect to an underlying service to perform a request.
    *
    * @param msg a human readable description of the cause
    */
  final case class DownstreamServiceError(override val msg: String) extends StorageError(msg)

  /**
    * Signals an attempt to interact with a path that doesn't exist.
    *
    * @param name the storage bucket name
    * @param path the relative path to the file
    */
  final case class PathNotFound(name: String, path: Path)
      extends StorageError(
        s"The provided location inside the bucket '$name' with the relative path '$path' does not exist."
      )

  /**
    * Signals an attempt to interact with a path that is invalid.
    *
    * @param name the storage bucket name
    * @param path the relative path to the file
    */
  final case class PathInvalid(name: String, path: Path)
      extends StorageError(
        s"The provided location inside the bucket '$name' with the relative path '$path' is invalid."
      )

  /**
    * Signals that the system call to the 'nexus-fixer' binary failed.
    *
    * @param path    the absolute path to the file
    * @param message the error message returned by the system call
    */
  final case class PermissionsFixingFailed(path: String, message: String)
      extends StorageError(s"Fixing permissions on the path '$path' failed with an error: $message")

  /**
    * Signals an internal timeout.
    *
    * @param msg a descriptive message on the operation that timed out
    */
  final case class OperationTimedOut(override val msg: String) extends StorageError(msg)

  @nowarn("cat=unused")
  implicit private val config: Configuration = Configuration.default.withDiscriminator("@type")

  private val derivedEncoder = deriveConfiguredEncoder[StorageError].mapJson(jsonError)

  implicit val storageErrorEncoder: Encoder[StorageError]       =
    Encoder.instance(r => derivedEncoder(r) deepMerge Json.obj("reason" -> Json.fromString(r.msg)))

  implicit val storageErrorStatusFrom: StatusFrom[StorageError] = {
    case _: PathNotFound      => StatusCodes.NotFound
    case _: PathInvalid       => StatusCodes.BadRequest
    case AuthenticationFailed => StatusCodes.Unauthorized
    case AuthorizationFailed  => StatusCodes.Forbidden
    case _                    => StatusCodes.InternalServerError
  }
}
