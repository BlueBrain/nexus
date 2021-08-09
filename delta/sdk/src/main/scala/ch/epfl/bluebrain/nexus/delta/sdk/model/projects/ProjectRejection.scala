package ch.epfl.bluebrain.nexus.delta.sdk.model.projects

import ch.epfl.bluebrain.nexus.delta.kernel.Mapper
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClassUtils
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClassUtils.simpleName
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.error.ServiceError.ScopeInitializationFailed
import ch.epfl.bluebrain.nexus.delta.sdk.model.TagLabel
import ch.epfl.bluebrain.nexus.delta.sdk.model.organizations.OrganizationRejection
import ch.epfl.bluebrain.nexus.delta.sdk.model.quotas.QuotaRejection
import ch.epfl.bluebrain.nexus.delta.sdk.model.quotas.QuotaRejection.QuotaReached
import ch.epfl.bluebrain.nexus.delta.sourcing.processor.AggregateResponse.{EvaluationError, EvaluationFailure, EvaluationTimeout}
import io.circe.syntax._
import io.circe.{Encoder, JsonObject}

import java.util.UUID
import scala.reflect.ClassTag

/**
  * Enumeration of Project rejection types.
  *
  * @param reason a descriptive message as to why the rejection occurred
  */
sealed abstract class ProjectRejection(val reason: String) extends Product with Serializable

object ProjectRejection {

  /**
    * Enumeration of possible reasons why a project is not found
    */
  sealed abstract class NotFound(reason: String) extends ProjectRejection(reason)

  /**
    * Rejection returned when a subject intends to retrieve a project at a specific revision, but the provided revision
    * does not exist.
    *
    * @param provided the provided revision
    * @param current  the last known revision
    */
  final case class RevisionNotFound(provided: Long, current: Long)
      extends NotFound(s"Revision requested '$provided' not found, last known revision is '$current'.")

  /**
    * Signals that an operation on a project cannot be performed due to the fact that the referenced project does not exist.
    */
  final case class ProjectNotFound private (override val reason: String) extends NotFound(reason)
  object ProjectNotFound {
    def apply(uuid: UUID): ProjectNotFound                       =
      ProjectNotFound(s"Project with uuid '${uuid.toString.toLowerCase()}' not found.")
    def apply(orgUuid: UUID, projectUuid: UUID): ProjectNotFound =
      ProjectNotFound(
        s"Project with uuid '${projectUuid.toString.toLowerCase()}' under organization: '${orgUuid.toString.toLowerCase()}' not found."
      )
    def apply(projectRef: ProjectRef): ProjectNotFound           =
      ProjectNotFound(s"Project '$projectRef' not found.")

    def apply(projectRef: ProjectRef, tag: TagLabel): ProjectNotFound =
      ProjectNotFound(s"Project '$projectRef' with tag '$tag' not found.")
  }

  /**
    * Signals that a project cannot be created because one with the same identifier already exists.
    */
  final case class ProjectAlreadyExists(projectRef: ProjectRef)
      extends ProjectRejection(s"Project '$projectRef' already exists.")

  /**
    * Signals a rejection caused when interacting with the organizations API
    */
  final case class WrappedOrganizationRejection(rejection: OrganizationRejection)
      extends ProjectRejection(rejection.reason)

  /**
    * Signals and attempt to update/deprecate a project that is already deprecated.
    */
  final case class ProjectIsDeprecated private (override val reason: String) extends ProjectRejection(reason)
  object ProjectIsDeprecated {
    def apply(uuid: UUID): ProjectIsDeprecated             =
      ProjectIsDeprecated(s"Project with uuid '${uuid.toString.toLowerCase()}' is deprecated.")
    def apply(projectRef: ProjectRef): ProjectIsDeprecated =
      ProjectIsDeprecated(s"Project '$projectRef' is deprecated.")
  }

  /**
    * Signals that a project update cannot be performed due to an incorrect revision provided.
    *
    * @param provided the provided revision
    * @param expected   latest know revision
    */
  final case class IncorrectRev(provided: Long, expected: Long)
      extends ProjectRejection(
        s"Incorrect revision '$provided' provided, expected '$expected', the project may have been updated since last seen."
      )

  /**
    * Rejection returned when the returned state is the initial state after a Project.evaluation plus a Project.next
    * Note: This should never happen since the evaluation method already guarantees that the next function returns a current
    */
  final case class UnexpectedInitialState(ref: ProjectRef)
      extends ProjectRejection(s"Unexpected initial state for project '$ref'.")

  /**
    * Rejection returned when the project initialization could not be performed.
    *
    * @param failure the underlying failure
    */
  final case class ProjectInitializationFailed(failure: ScopeInitializationFailed)
      extends ProjectRejection(s"The project has been successfully created but it could not be initialized correctly")

  /**
    * Rejection returned when attempting to evaluate a command but the evaluation failed
    */
  final case class ProjectEvaluationError(err: EvaluationError) extends ProjectRejection("Unexpected evaluation error")

  /**
    * Signals a rejection caused when interacting with the quotas API
    */
  final case class WrappedQuotaRejection(rejection: QuotaReached) extends ProjectRejection(rejection.reason)

  implicit val organizationRejectionMapper: Mapper[OrganizationRejection, ProjectRejection] =
    (value: OrganizationRejection) => WrappedOrganizationRejection(value)

  implicit val projectQuotasRejectionMapper: Mapper[QuotaReached, WrappedQuotaRejection] =
    WrappedQuotaRejection(_)

  implicit def projectRejectionEncoder(implicit C: ClassTag[ProjectCommand]): Encoder.AsObject[ProjectRejection] =
    Encoder.AsObject.instance { r =>
      val tpe     = ClassUtils.simpleName(r)
      val default = JsonObject.empty.add(keywords.tpe, tpe.asJson).add("reason", r.reason.asJson)
      r match {
        case ProjectEvaluationError(EvaluationFailure(C(cmd), _)) =>
          val reason = s"Unexpected failure while evaluating the command '${simpleName(cmd)}' for project '${cmd.ref}'"
          JsonObject(keywords.tpe -> "ProjectEvaluationFailure".asJson, "reason" -> reason.asJson)
        case ProjectEvaluationError(EvaluationTimeout(C(cmd), t)) =>
          val reason = s"Timeout while evaluating the command '${simpleName(cmd)}' for project '${cmd.ref}' after '$t'"
          JsonObject(keywords.tpe -> "ProjectEvaluationTimeout".asJson, "reason" -> reason.asJson)
        case WrappedQuotaRejection(rejection)                     => (rejection: QuotaRejection).asJsonObject
        case WrappedOrganizationRejection(rejection)              => rejection.asJsonObject
        case ProjectInitializationFailed(rejection)               => default.add("details", rejection.reason.asJson)
        case IncorrectRev(provided, expected)                     =>
          default.add("provided", provided.asJson).add("expected", expected.asJson)
        case ProjectAlreadyExists(projectRef)                     =>
          default.add("label", projectRef.project.asJson).add("orgLabel", projectRef.organization.asJson)
        case _                                                    => default

      }
    }

  implicit final val projectRejectionJsonLdEncoder: JsonLdEncoder[ProjectRejection] =
    JsonLdEncoder.computeFromCirce(ContextValue(contexts.error))

  implicit final val evaluationErrorMapper: Mapper[EvaluationError, ProjectRejection] = ProjectEvaluationError.apply
}
