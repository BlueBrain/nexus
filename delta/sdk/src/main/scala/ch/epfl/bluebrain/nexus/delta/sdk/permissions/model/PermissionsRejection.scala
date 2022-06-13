package ch.epfl.bluebrain.nexus.delta.sdk.permissions.model

import akka.http.scaladsl.model.StatusCodes
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClassUtils
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.HttpResponseFields
import io.circe.syntax._
import io.circe.{Encoder, JsonObject}

/**
  * Enumeration of Permissions rejection types.
  *
  * @param reason
  *   a descriptive message as to why the rejection occurred
  */
sealed abstract class PermissionsRejection(val reason: String) extends Product with Serializable

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
        s"Cannot subtract permissions from the minimum collection of permissions: ${permissions.mkString("'", "', '", "'")}"
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
        s"Cannot subtract permissions not present in the collection: ${permissions.mkString("'", "', '", "'")}."
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
    * @param provided
    *   the provided revision
    * @param expected
    *   the expected revision
    */
  final case class IncorrectRev(provided: Int, expected: Int)
      extends PermissionsRejection(
        s"Incorrect revision '$provided' provided, expected '$expected', permissions may have been updated since last seen."
      )

  /**
    * Rejection returned when a subject intends to retrieve the collection of permissions at a specific revision, but
    * the provided revision does not exist.
    *
    * @param provided
    *   the provided revision
    * @param current
    *   the last known revision
    */
  final case class RevisionNotFound(provided: Int, current: Int)
      extends PermissionsRejection(s"Revision requested '$provided' not found, last known revision is '$current'.")

  /**
    * Rejection returned when there is no state for permissions Note: This should never happen since an initial state is
    * provided
    */
  sealed abstract class UnexpectedState(reason: String) extends PermissionsRejection(reason)
  final case object UnexpectedState
      extends UnexpectedState(s"Unexpected state for permissions. Please try again later or contact the administrator.")

  implicit val permissionsRejectionEncoder: Encoder.AsObject[PermissionsRejection] =
    Encoder.AsObject.instance { r =>
      val tpe     = ClassUtils.simpleName(r)
      val default = JsonObject.empty.add(keywords.tpe, tpe.asJson).add("reason", r.reason.asJson)
      r match {
        case IncorrectRev(provided, expected) =>
          default.add("provided", provided.asJson).add("expected", expected.asJson)
        case _                                => default
      }
    }

  implicit final val permissionsRejectionJsonLdEncoder: JsonLdEncoder[PermissionsRejection] =
    JsonLdEncoder.computeFromCirce(ContextValue(contexts.error))

  implicit val responseFieldsPermissions: HttpResponseFields[PermissionsRejection] =
    HttpResponseFields {
      case PermissionsRejection.IncorrectRev(_, _)     => StatusCodes.Conflict
      case PermissionsRejection.RevisionNotFound(_, _) => StatusCodes.NotFound
      case UnexpectedState                             => StatusCodes.InternalServerError
      case _                                           => StatusCodes.BadRequest
    }

}
