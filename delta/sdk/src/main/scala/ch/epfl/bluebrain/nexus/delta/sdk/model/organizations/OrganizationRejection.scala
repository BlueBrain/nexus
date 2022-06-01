package ch.epfl.bluebrain.nexus.delta.sdk.model.organizations

import ch.epfl.bluebrain.nexus.delta.kernel.Mapper
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClassUtils
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClassUtils.simpleName
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.error.ServiceError.ScopeInitializationFailed
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import ch.epfl.bluebrain.nexus.delta.sourcing.processor.AggregateResponse.{EvaluationError, EvaluationFailure, EvaluationTimeout}
import io.circe.syntax._
import io.circe.{Encoder, JsonObject}

import java.util.UUID
import scala.reflect.ClassTag

/**
  * Enumeration of organization rejection types.
  *
  * @param reason
  *   a descriptive message as to why the rejection occurred
  */
sealed abstract class OrganizationRejection(val reason: String) extends Product with Serializable

object OrganizationRejection {

  /**
    * Enumeration of possible reasons why an organization is not found
    */
  sealed abstract class NotFound(reason: String) extends OrganizationRejection(reason)

  /**
    * Signals that the organization does not exist.
    */
  final case class OrganizationNotFound private (override val reason: String) extends NotFound(reason)

  /**
    * Signals an attempt to retrieve an organization at a specific revision when the provided revision does not exist.
    *
    * @param provided
    *   the provided revision
    * @param current
    *   the last known revision
    */
  final case class RevisionNotFound(provided: Long, current: Long)
      extends NotFound(s"Revision requested '$provided' not found, last known revision is '$current'.")

  object OrganizationNotFound {
    def apply(uuid: UUID): OrganizationNotFound =
      new OrganizationNotFound(s"Organization with uuid '${uuid.toString.toLowerCase()}' not found.")

    def apply(label: Label): OrganizationNotFound =
      new OrganizationNotFound(s"Organization '$label' not found.")
  }

  /**
    * Signals the the organization already exists.
    */
  final case class OrganizationAlreadyExists(label: Label)
      extends OrganizationRejection(s"Organization '$label' already exists.")

  /**
    * Signals that the provided revision does not match the latest revision
    *
    * @param provided
    *   provided revision
    * @param expected
    *   latest know revision
    */
  final case class IncorrectRev(provided: Long, expected: Long)
      extends OrganizationRejection(
        s"Incorrect revision '$provided' provided, expected '$expected', the organization may have been updated since last seen."
      )

  /**
    * Signals and attempt to update/deprecate an organization that is already deprecated.
    *
    * @param label
    *   the label of the organization
    */
  final case class OrganizationIsDeprecated(label: Label)
      extends OrganizationRejection(s"Organization '$label' is deprecated.")

  /**
    * Rejection returned when the state is the initial after a Organizations.evaluation plus a Organizations.next Note:
    * This should never happen since the evaluation method already guarantees that the next function returns a current
    */
  final case class UnexpectedInitialState(label: Label)
      extends OrganizationRejection(s"Unexpected initial state for organization '$label'.")

  /**
    * Rejection returned when the organization initialization could not be performed.
    *
    * @param failure
    *   the underlying failure
    */
  final case class OrganizationInitializationFailed(failure: ScopeInitializationFailed)
      extends OrganizationRejection(
        s"The organization has been successfully created but it could not be initialized due to: '${failure.reason}'"
      )

  /**
    * Rejection returned when attempting to evaluate a command but the evaluation failed
    */
  final case class OrganizationEvaluationError(err: EvaluationError)
      extends OrganizationRejection("Unexpected evaluation error")

  implicit def orgRejectionEncoder(implicit C: ClassTag[OrganizationCommand]): Encoder.AsObject[OrganizationRejection] =
    Encoder.AsObject.instance { r =>
      val tpe     = ClassUtils.simpleName(r)
      val default = JsonObject.empty.add(keywords.tpe, tpe.asJson).add("reason", r.reason.asJson)
      r match {
        case OrganizationEvaluationError(EvaluationFailure(C(cmd), _)) =>
          val reason =
            s"Unexpected failure while evaluating the command '${simpleName(cmd)}' for organization '${cmd.label}'"
          JsonObject(keywords.tpe -> "OrganizationEvaluationFailure".asJson, "reason" -> reason.asJson)
        case OrganizationEvaluationError(EvaluationTimeout(C(cmd), t)) =>
          val reason =
            s"Timeout while evaluating the command '${simpleName(cmd)}' for organization '${cmd.label}' after '$t'"
          JsonObject(keywords.tpe -> "OrganizationEvaluationTimeout".asJson, "reason" -> reason.asJson)
        case OrganizationAlreadyExists(orgLabel)                       => default.add("label", orgLabel.asJson)
        case IncorrectRev(provided, expected)                          =>
          default.add("provided", provided.asJson).add("expected", expected.asJson)
        case _                                                         => default
      }

    }

  implicit final val orgRejectionJsonLdEncoder: JsonLdEncoder[OrganizationRejection] =
    JsonLdEncoder.computeFromCirce(ContextValue(contexts.error))

  implicit final val evaluationErrorMapper: Mapper[EvaluationError, OrganizationRejection] =
    OrganizationEvaluationError.apply

}
