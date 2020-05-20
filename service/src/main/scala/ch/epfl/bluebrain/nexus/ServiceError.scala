package ch.epfl.bluebrain.nexus

import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.auth.TokenRejection
import ch.epfl.bluebrain.nexus.permissions.Permission
import ch.epfl.bluebrain.nexus.utils.Codecs
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredEncoder
import io.circe.{Encoder, Json}

/**
  * Generic error types global to the entire service.
  *
  * @param msg the reason why the error occurred
  */
@SuppressWarnings(Array("IncorrectlyNamedExceptions"))
sealed abstract class ServiceError(val msg: String) extends Exception with Product with Serializable {
  override def fillInStackTrace(): Throwable = this
  override def getMessage: String            = msg
}

@SuppressWarnings(Array("IncorrectlyNamedExceptions"))
object ServiceError extends Codecs {

  /**
    * Signals the failure to perform an action on a resource, because of lacking permission.
    *
    * @param resource   the resource on which the action was attempted
    * @param permission the missing permission
    */
  final case class AccessDenied(resource: Uri, permission: Permission)
      extends ServiceError(s"Access '${permission.value}' to resource '$resource' denied.")

  /**
    * Signals an unexpected state was detected after a command evaluation.
    *
    * @param resource the resource on which the action was attempted
    */
  final case class UnexpectedInitialState(resource: Uri)
      extends ServiceError(s"Unexpected state on resource '$resource'.")

  /**
    * Signals an internal timeout.
    *
    * @param reason a descriptive message on the operation that timed out
    */
  final case class OperationTimedOut(reason: String) extends ServiceError(reason)

  /**
    * Generic wrapper for iam errors that should not be exposed to clients.
    *
    * @param reason the underlying error reason
    */
  final case class InternalError(reason: String) extends ServiceError(reason)

  /**
    * Signals that an error occurred while attempting to perform an operation with an invalid access token.
    *
    * @param rejection a reason for why the token is considered invalid
    */
  final case class InvalidAccessToken(rejection: TokenRejection)
      extends ServiceError("The provided access token is invalid.")

  /**
    * Signals that the requested resource was not found
    */
  final case object NotFound extends ServiceError("The requested resource could not be found.")

  implicit private[ServiceError] val rejectionConfig: Configuration = Configuration.default.withDiscriminator("@type")
  implicit val iamErrorEncoder: Encoder[ServiceError] = {
//    val enc = deriveConfiguredEncoder[ServiceError].mapJson(_ addContext errorCtxUri)
    val enc = deriveConfiguredEncoder[ServiceError]
    Encoder.instance(r => enc(r) deepMerge Json.obj("reason" -> Json.fromString(r.msg)))
  }
}
