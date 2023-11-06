package ch.epfl.bluebrain.nexus.delta.sdk.organizations.model

import akka.http.scaladsl.model.StatusCodes
import ch.epfl.bluebrain.nexus.delta.kernel.error.Rejection
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClassUtils
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.error.ServiceError.ScopeInitializationFailed
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.HttpResponseFields
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import io.circe.syntax._
import io.circe.{Encoder, JsonObject}

/**
  * Enumeration of organization rejection types.
  *
  * @param reason
  *   a descriptive message as to why the rejection occurred
  */
sealed abstract class OrganizationRejection(val reason: String) extends Rejection

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
  final case class RevisionNotFound(provided: Int, current: Int)
      extends NotFound(s"Revision requested '$provided' not found, last known revision is '$current'.")

  object OrganizationNotFound {
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
  final case class IncorrectRev(provided: Int, expected: Int)
      extends OrganizationRejection(
        s"Incorrect revision '$provided' provided, expected '$expected', the organization may have been updated since last seen."
      )

  /**
    * Signals an attempt to update/deprecate an organization that is already deprecated.
    *
    * @param label
    *   the label of the organization
    */
  final case class OrganizationIsDeprecated(label: Label)
      extends OrganizationRejection(s"Organization '$label' is deprecated.")

  /**
    * Signals an attempt to delete an organization that contains at least one project.
    *
    * @param label
    *   the label of the organization
    */
  final case class OrganizationNonEmpty(label: Label)
      extends OrganizationRejection(s"Organization '$label' cannot be deleted since it contains at least one project.")

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

  implicit val orgRejectionEncoder: Encoder.AsObject[OrganizationRejection] =
    Encoder.AsObject.instance { r =>
      val tpe     = ClassUtils.simpleName(r)
      val default = JsonObject.empty.add(keywords.tpe, tpe.asJson).add("reason", r.reason.asJson)
      r match {
        case OrganizationAlreadyExists(orgLabel) => default.add("label", orgLabel.asJson)
        case IncorrectRev(provided, expected)    =>
          default.add("provided", provided.asJson).add("expected", expected.asJson)
        case _                                   => default
      }

    }

  implicit final val orgRejectionJsonLdEncoder: JsonLdEncoder[OrganizationRejection] =
    JsonLdEncoder.computeFromCirce(ContextValue(contexts.error))

  implicit val responseFieldsOrganizations: HttpResponseFields[OrganizationRejection] =
    HttpResponseFields {
      case OrganizationRejection.OrganizationNotFound(_)      => StatusCodes.NotFound
      case OrganizationRejection.OrganizationAlreadyExists(_) => StatusCodes.Conflict
      case OrganizationRejection.IncorrectRev(_, _)           => StatusCodes.Conflict
      case OrganizationRejection.OrganizationNonEmpty(_)      => StatusCodes.Conflict
      case OrganizationRejection.RevisionNotFound(_, _)       => StatusCodes.NotFound
      case _                                                  => StatusCodes.BadRequest
    }

}
