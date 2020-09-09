package ch.epfl.bluebrain.nexus.kg

import akka.http.scaladsl.model.{StatusCodes, Uri}
import cats.syntax.show._
import ch.epfl.bluebrain.nexus.commons.http.directives.StatusFrom
import ch.epfl.bluebrain.nexus.kg.config.Contexts.errorCtxUri
import ch.epfl.bluebrain.nexus.kg.resources.Ref
import ch.epfl.bluebrain.nexus.kg.resources.ProjectIdentifier.ProjectLabel
import ch.epfl.bluebrain.nexus.rdf.implicits._
import scala.annotation.nowarn
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredEncoder
import io.circe.{Encoder, Json}
// $COVERAGE-OFF$
/**
  * Enumeration of runtime errors.
  *
  * @param msg a description of the error
  */

sealed abstract class KgError(val msg: String) extends Exception with Product with Serializable {
  override def fillInStackTrace(): KgError = this
  override def getMessage: String          = msg
}

object KgError {

  /**
    * Generic wrapper for kg errors that should not be exposed to clients.
    *
    * @param reason the underlying error reason
    */
  final case class InternalError(reason: String) extends KgError(reason)

  /**
    * Signals that the file being uploaded is bigger than the allowed size limit
    */
  final case class FileSizeExceed(limit: Long, actual: Option[Long])
      extends KgError(s"File maximum size exceed. Limit '$limit'")

  /**
    * Signals that the operation is not supported
    */
  case object UnsupportedOperation extends KgError("The operation is not supported by the system.")

  /**
    * Signals that the requested resource was not found
    */
  final case class NotFound(ref: Option[String] = None)
      extends KgError(s"The requested resource could not be found '$ref'.")

  object NotFound {
    def apply(ref: Ref): NotFound =
      NotFound(Some(ref.show))
  }

  /**
    * Signals the impossibility to reach a remote file.
    *
    * @param location the remote file URI
    */
  final case class RemoteFileNotFound(location: Uri) extends KgError("The remote file was not found.")

  /**
    * Signals an error on the downstream storage service.
    *
    * @param msg a human readable description of the cause
    */
  final case class RemoteStorageError(override val msg: String) extends KgError(msg)

  /**
    * Signals that the provided authentication is not valid.
    */
  final case object AuthenticationFailed extends KgError("The supplied authentication is invalid.")

  /**
    * Signals that the provided output format name is not valid.
    *
    * @param name the provided output format name
    */
  final case class InvalidOutputFormat(name: String) extends KgError(s"The supplied output format '$name' is invalid.")

  /**
    * Signals that the caller doesn't have access to the selected resource.
    */
  final case object AuthorizationFailed
      extends KgError("The supplied authentication is not authorized to access this resource.")

  /**
    * Signals the inability to connect to an underlying service to perform a request.
    *
    * @param msg a human readable description of the cause
    */
  final case class DownstreamServiceError(override val msg: String) extends KgError(msg)

  /**
    * Signals an internal timeout.
    *
    * @param msg a descriptive message on the operation that timed out
    */
  final case class OperationTimedOut(override val msg: String) extends KgError(msg)

  /**
    * Signals the impossibility to resolve the project reference for project labels.
    *
    * @param label the project label
    */
  final case class ProjectNotFound(label: ProjectLabel) extends KgError(s"Project '${label.show}' not found.")

  /**
    * Signals the impossibility to resolve the organization reference from a given label.
    *
    * @param label the organization label
    */
  final case class OrganizationNotFound(label: String) extends KgError(s"Organization '$label' not found.")

  /**
    * Signals the HTTP Accept Headers are not compatible with the response Content-Type.
    *
    * @param reason a descriptive message
    */
  final case class UnacceptedResponseContentType(reason: String) extends KgError(reason)

  /**
    * Signals an attempt to interact with a resource that belongs to a deprecated project.
    *
    * @param ref a reference to the project
    */
  final case class ProjectIsDeprecated(ref: ProjectLabel) extends KgError(s"Project '${ref.show}' is deprecated.")

  @nowarn("cat=unused") // private implicits in automatic derivation are not recognized as used
  implicit val kgErrorEncoder: Encoder[KgError] = {
    implicit val config: Configuration = Configuration.default.withDiscriminator("@type")
    implicit val uriEnc: Encoder[Uri]  = Encoder.encodeString.contramap(_.toString)

    val enc = deriveConfiguredEncoder[KgError].mapJson(_ addContext errorCtxUri)
    Encoder.instance(r => enc(r).removeKeys("msg") deepMerge Json.obj("reason" -> Json.fromString(r.msg)))
  }

  implicit val kgErrorStatusFrom: StatusFrom[KgError] = {
    case _: FileSizeExceed                => StatusCodes.PayloadTooLarge
    case _: NotFound                      => StatusCodes.NotFound
    case _: ProjectNotFound               => StatusCodes.NotFound
    case _: OrganizationNotFound          => StatusCodes.NotFound
    case _: RemoteFileNotFound            => StatusCodes.BadGateway
    case AuthenticationFailed             => StatusCodes.Unauthorized
    case AuthorizationFailed              => StatusCodes.Forbidden
    case _: ProjectIsDeprecated           => StatusCodes.BadRequest
    case _: InvalidOutputFormat           => StatusCodes.BadRequest
    case UnsupportedOperation             => StatusCodes.BadRequest
    case _: UnacceptedResponseContentType => StatusCodes.NotAcceptable
    case _                                => StatusCodes.InternalServerError
  }
}
// $COVERAGE-ON$
