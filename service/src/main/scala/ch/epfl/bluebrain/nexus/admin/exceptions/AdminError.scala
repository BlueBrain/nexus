package ch.epfl.bluebrain.nexus.admin.exceptions

import akka.http.scaladsl.model.StatusCodes
import ch.epfl.bluebrain.nexus.admin.config.Contexts._
import ch.epfl.bluebrain.nexus.commons.http.directives.StatusFrom
import ch.epfl.bluebrain.nexus.rdf.implicits._
import com.github.ghik.silencer.silent
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredEncoder
import io.circe.{Encoder, Json}

/**
  * Generic error types global to the entire service.
  *
  * @param msg the reason why the error occurred
  */
@SuppressWarnings(Array("IncorrectlyNamedExceptions"))
sealed abstract class AdminError(val msg: String) extends Exception with Product with Serializable {
  override def fillInStackTrace(): Throwable = this
  override def getMessage: String            = msg
}

@SuppressWarnings(Array("IncorrectlyNamedExceptions"))
object AdminError {

  /**
    * Signals that the resource is in an unexpected state.
    *
    * @param id ID of the resource
    */
  final case class UnexpectedState(id: String) extends AdminError(s"Unexpected resource state for resource with ID $id")

  /**
    * Signals an internal timeout.
    *
    * @param reason a descriptive message on the operation that timed out
    */
  final case class OperationTimedOut(reason: String) extends AdminError(reason)

  /**
    * Generic wrapper for iam errors that should not be exposed to clients.
    *
    * @param reason the underlying error reason
    */
  final case class InternalError(reason: String) extends AdminError(reason)

  /**
    * Signals that the requested resource was not found
    */
  final case object NotFound extends AdminError("The requested resource could not be found.")

  /**
    * Signals that the provided authentication is not valid.
    */
  final case object AuthenticationFailed extends AdminError("The supplied authentication is invalid.")

  /**
    * Signals that the caller doesn't have access to the selected resource.
    */
  final case object AuthorizationFailed
      extends AdminError("The supplied authentication is not authorized to access this resource.")

  /**
    * Signals an error while decoding a JSON payload.
    */
  final case object InvalidFormat extends AdminError("The json representation is incorrectly formatted.")

  @silent
  implicit val adminErrorEncoder: Encoder[AdminError] = {
    implicit val rejectionConfig: Configuration = Configuration.default.withDiscriminator("@type")
    val enc                                     = deriveConfiguredEncoder[AdminError].mapJson(_ addContext errorCtxUri)
    Encoder.instance(r => enc(r) deepMerge Json.obj("reason" -> Json.fromString(r.msg)))
  }

  implicit val adminErrorStatusFrom: StatusFrom[AdminError] = {
    case NotFound             => StatusCodes.NotFound
    case AuthenticationFailed => StatusCodes.Unauthorized
    case AuthorizationFailed  => StatusCodes.Forbidden
    case InvalidFormat        => StatusCodes.BadRequest
    case _                    => StatusCodes.InternalServerError
  }
}
