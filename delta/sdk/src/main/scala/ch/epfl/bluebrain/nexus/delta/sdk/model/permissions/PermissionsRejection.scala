package ch.epfl.bluebrain.nexus.delta.sdk.model.permissions

import ch.epfl.bluebrain.nexus.delta.kernel.Mapper
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClassUtils
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sourcing.processor.AggregateResponse.{EvaluationError, EvaluationFailure, EvaluationTimeout}
import io.circe.syntax._
import io.circe.{Encoder, JsonObject}

import scala.concurrent.duration.FiniteDuration
import scala.reflect.ClassTag

/**
  * Enumeration of Permissions rejection types.
  *
  * @param reason a descriptive message as to why the rejection occurred
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

  /**
    * Rejection returned when a subject intends to retrieve the collection of permissions at a specific revision, but
    * the provided revision does not exist.
    *
    * @param provided the provided revision
    * @param current  the last known revision
    */
  final case class RevisionNotFound(provided: Long, current: Long)
      extends PermissionsRejection(s"Revision requested '$provided' not found, last known revision is '$current'.")

  /**
    * Rejection returned when attempting to evaluate a command but the evaluation took more than the configured maximum value
    */
  final case class PermissionsEvaluationTimeout(command: PermissionsCommand, timeoutAfter: FiniteDuration)
      extends PermissionsRejection(
        s"Timeout while evaluating the command '${ClassUtils.simpleName(command)}' after '$timeoutAfter'"
      )

  /**
    * Rejection returned when attempting to evaluate a command but the evaluation took more than the configured maximum value
    */
  final case class PermissionsEvaluationFailure(command: PermissionsCommand)
      extends PermissionsRejection(
        s"Unexpected failure while evaluating the command '${ClassUtils.simpleName(command)}'"
      )

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

  implicit final def evaluationErrorMapper(implicit
      C: ClassTag[PermissionsCommand]
  ): Mapper[EvaluationError, PermissionsRejection] = {
    case EvaluationFailure(C(cmd), _)            => PermissionsEvaluationFailure(cmd)
    case EvaluationTimeout(C(cmd), timeoutAfter) => PermissionsEvaluationTimeout(cmd, timeoutAfter)
    case EvaluationFailure(cmd, _)               =>
      throw new IllegalArgumentException(
        s"Expected an EvaluationFailure of 'PermissionsCommand', found '${ClassUtils.simpleName(cmd)}'"
      )
    case EvaluationTimeout(cmd, _)               =>
      throw new IllegalArgumentException(
        s"Expected an EvaluationTimeout of 'PermissionsCommand', found '${ClassUtils.simpleName(cmd)}'"
      )
  }
}
