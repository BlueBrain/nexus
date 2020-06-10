package ch.epfl.bluebrain.nexus.iam.permissions

import akka.http.scaladsl.model.StatusCodes._
import ch.epfl.bluebrain.nexus.commons.http.directives.StatusFrom
import ch.epfl.bluebrain.nexus.iam.types.Permission
import ch.epfl.bluebrain.nexus.rdf.implicits._
import ch.epfl.bluebrain.nexus.service.config.Contexts.errorCtxUri
import ch.epfl.bluebrain.nexus.service.routes.ResourceRejection
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredEncoder
import io.circe.{Encoder, Json}

/**
  * Enumeration of Permissions rejection types.
  *
  * @param msg a descriptive message for why the rejection occurred
  */
sealed abstract class PermissionsRejection(val msg: String) extends ResourceRejection

object PermissionsRejection {

  /**
    * Rejection returned when a subject intends to subtract an empty collection of permissions.
    */
  final case object CannotSubtractEmptyCollection
      extends PermissionsRejection("Cannot subtract an empty collection of permissions.")

  /**
    * Rejection returned when a subject intends to subtract from the minimum collection of permissions.
    */
  final case class CannotSubtractFromMinimumCollection(permissions: Set[Permission])
      extends PermissionsRejection(
        s"Cannot subtract permissions from the minimum collection of permissions: '${permissions.mkString("\"", ", ", "\"")}'"
      )

  /**
    * Rejection returned when a subject intends to subtract permissions when the current collection is empty.
    */
  final case object CannotSubtractFromEmptyCollection
      extends PermissionsRejection("Cannot subtract from an empty collection of permissions.")

  /**
    * Rejection returned when a subject intends to subtract permissions that are not in the current collection.
    */
  final case class CannotSubtractUndefinedPermissions(permissions: Set[Permission])
      extends PermissionsRejection(
        s"Cannot subtract permissions not present in the collection: '${permissions.mkString("\"", ", ", "\"")}'."
      )

  /**
    * Rejection returned when a subject intends to append an empty collection of permissions.
    */
  final case object CannotAppendEmptyCollection
      extends PermissionsRejection("Cannot append an empty collection of permissions.")

  /**
    * Rejection returned when a subject intends to replace the current collection of permission with an empty set.
    */
  final case object CannotReplaceWithEmptyCollection
      extends PermissionsRejection("Cannot replace the permissions with an empty collection.")

  /**
    * Rejection returned when a subject intends to delete (empty) the current collection of permissions, but the
    * collection is already empty.
    */
  final case object CannotDeleteMinimumCollection
      extends PermissionsRejection("Cannot delete the minimum collection of permissions.")

  /**
    * Rejection returned when a subject intends to perform an operation on the current collection of permissions, but
    * either provided an incorrect revision or a concurrent update won over this attempt.
    *
    * @param provided the provided revision
    * @param expected the expected revision
    */
  final case class IncorrectRev(provided: Long, expected: Long)
      extends PermissionsRejection(
        s"Incorrect revision '$provided' provided, expected '$expected', permissions may have been updated since last seen."
      )

  implicit private[PermissionsRejection] val rejectionConfig: Configuration =
    Configuration.default.withDiscriminator("@type")

  implicit val permissionRejectionEncoder: Encoder[PermissionsRejection] = {
    val enc = deriveConfiguredEncoder[PermissionsRejection].mapJson(_ addContext errorCtxUri)
    Encoder.instance(r => enc(r) deepMerge Json.obj("reason" -> Json.fromString(r.msg)))
  }

  implicit val permissionsRejectionStatusFrom: StatusFrom[PermissionsRejection] =
    StatusFrom {
      case CannotSubtractEmptyCollection          => BadRequest
      case _: CannotSubtractFromMinimumCollection => BadRequest
      case CannotSubtractFromEmptyCollection      => BadRequest
      case _: CannotSubtractUndefinedPermissions  => BadRequest
      case CannotAppendEmptyCollection            => BadRequest
      case CannotReplaceWithEmptyCollection       => BadRequest
      case CannotDeleteMinimumCollection          => BadRequest
      case _: IncorrectRev                        => Conflict
    }
}
