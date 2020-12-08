package ch.epfl.bluebrain.nexus.delta.sdk.model.projects

import java.util.UUID

import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClassUtils
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.Mapper
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.AclRejection
import ch.epfl.bluebrain.nexus.delta.sdk.model.organizations.OrganizationRejection
import io.circe.syntax._
import io.circe.{Encoder, JsonObject}

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
    * Rejection returned when applying owner permissions with the acl module during project creation fails
    */
  final case class OwnerPermissionsFailed(ref: ProjectRef, aclRejection: AclRejection)
      extends ProjectRejection(
        s"The project has been successfully created but applying owner permissions on project '$ref' failed with the following error: ${aclRejection.reason}"
      )

  implicit val organizationRejectionMapper: Mapper[OrganizationRejection, ProjectRejection] =
    (value: OrganizationRejection) => WrappedOrganizationRejection(value)

  implicit val projectRejectionEncoder: Encoder.AsObject[ProjectRejection] =
    Encoder.AsObject.instance { r =>
      val tpe     = ClassUtils.simpleName(r)
      val default = JsonObject.empty.add(keywords.tpe, tpe.asJson).add("reason", r.reason.asJson)
      r match {
        case WrappedOrganizationRejection(rejection) => rejection.asJsonObject
        case IncorrectRev(provided, expected)        =>
          default.add("provided", provided.asJson).add("expected", expected.asJson)
        case ProjectAlreadyExists(projectRef)        =>
          default.add("label", projectRef.project.asJson).add("orgLabel", projectRef.organization.asJson)
        case _                                       => default

      }
    }

  implicit final val projectRejectionJsonLdEncoder: JsonLdEncoder[ProjectRejection] =
    JsonLdEncoder.computeFromCirce(ContextValue(contexts.error))
}
