package ch.epfl.bluebrain.nexus.delta.sdk.projects.model

import akka.http.scaladsl.model.StatusCodes
import ch.epfl.bluebrain.nexus.delta.kernel.Mapper
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClassUtils
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.error.ServiceError.ScopeInitializationFailed
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.HttpResponseFields
import ch.epfl.bluebrain.nexus.delta.sdk.organizations.model.OrganizationRejection
import ch.epfl.bluebrain.nexus.delta.sdk.projects.ProjectReferenceFinder.ProjectReferenceMap
import ch.epfl.bluebrain.nexus.delta.sdk.syntax.httpResponseFieldsSyntax
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import io.circe.syntax._
import io.circe.{Encoder, JsonObject}

/**
  * Enumeration of Project rejection types.
  *
  * @param reason
  *   a descriptive message as to why the rejection occurred
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
    * @param provided
    *   the provided revision
    * @param current
    *   the last known revision
    */
  final case class RevisionNotFound(provided: Int, current: Int)
      extends NotFound(s"Revision requested '$provided' not found, last known revision is '$current'.")

  /**
    * Signals that an operation on a project cannot be performed due to the fact that the referenced project does not
    * exist.
    */
  final case class ProjectNotFound(projectRef: ProjectRef) extends NotFound(s"Project '$projectRef' not found.")

  /**
    * Signals that the current project is expected to be deleted but it isn't
    */
  final case class ProjectNotDeleted(projectRef: ProjectRef)
      extends ProjectRejection(s"Project '$projectRef' is not marked for deletion")

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
  final case class ProjectIsDeprecated(projectRef: ProjectRef)
      extends ProjectRejection(s"Project '$projectRef' is deprecated.")

  /**
    * Signals and attempt to update/deprecate/delete a project that is already marked for deletion.
    */
  final case class ProjectIsMarkedForDeletion(projectRef: ProjectRef)
      extends ProjectRejection(s"Project '$projectRef' is marked for deletion.")

  final case class ProjectIsReferenced(projectRef: ProjectRef, references: ProjectReferenceMap)
      extends ProjectRejection(
        s"Project $projectRef can't be deleted as it is referenced by projects '${references.value.keys.mkString(", ")}'."
      )

  /**
    * Signals that a project update cannot be performed due to an incorrect revision provided.
    *
    * @param provided
    *   the provided revision
    * @param expected
    *   latest know revision
    */
  final case class IncorrectRev(provided: Int, expected: Int)
      extends ProjectRejection(
        s"Incorrect revision '$provided' provided, expected '$expected', the project may have been updated since last seen."
      )

  /**
    * Rejection returned when the project initialization could not be performed.
    *
    * @param failure
    *   the underlying failure
    */
  final case class ProjectInitializationFailed(failure: ScopeInitializationFailed)
      extends ProjectRejection(s"The project has been successfully created but it could not be initialized correctly")

  implicit val organizationRejectionMapper: Mapper[OrganizationRejection, ProjectRejection] =
    (value: OrganizationRejection) => WrappedOrganizationRejection(value)

  implicit val projectRejectionEncoder: Encoder.AsObject[ProjectRejection] =
    Encoder.AsObject.instance { r =>
      val tpe     = ClassUtils.simpleName(r)
      val default = JsonObject.empty.add(keywords.tpe, tpe.asJson).add("reason", r.reason.asJson)
      r match {
        case WrappedOrganizationRejection(rejection) => rejection.asJsonObject
        case ProjectInitializationFailed(rejection)  => default.add("details", rejection.reason.asJson)
        case ProjectIsReferenced(_, references)      => default.add("referencedBy", references.asJson)
        case IncorrectRev(provided, expected)        =>
          default.add("provided", provided.asJson).add("expected", expected.asJson)
        case ProjectAlreadyExists(projectRef)        =>
          default.add("label", projectRef.project.asJson).add("orgLabel", projectRef.organization.asJson)
        case _                                       => default

      }
    }

  implicit final val projectRejectionJsonLdEncoder: JsonLdEncoder[ProjectRejection] =
    JsonLdEncoder.computeFromCirce(ContextValue(contexts.error))

  implicit val responseFieldsProjects: HttpResponseFields[ProjectRejection] =
    HttpResponseFields {
      case ProjectRejection.RevisionNotFound(_, _)            => StatusCodes.NotFound
      case ProjectRejection.ProjectNotFound(_)                => StatusCodes.NotFound
      case ProjectRejection.WrappedOrganizationRejection(rej) => rej.status
      case ProjectRejection.ProjectAlreadyExists(_)           => StatusCodes.Conflict
      case ProjectRejection.IncorrectRev(_, _)                => StatusCodes.Conflict
      case _                                                  => StatusCodes.BadRequest
    }

}
