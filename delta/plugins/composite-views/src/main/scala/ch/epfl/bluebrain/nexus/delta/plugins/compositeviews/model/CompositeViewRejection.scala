package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model

import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewSource.{CrossProjectSource, RemoteProjectSource}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sourcing.processor.AggregateResponse.EvaluationError
import io.circe.Json

/**
  * Enumeration of composite view rejection types.
  *
  * @param reason a descriptive message as to why the rejection occurred
  */
sealed abstract class CompositeViewRejection(val reason: String) extends Product with Serializable

object CompositeViewRejection {

  /**
    * Signals a rejection caused by an attempt to create or update an composite view with a permission that is not
    * defined in the permission set singleton.
    *
    * @param permission the provided permission
    */
  final case class PermissionIsNotDefined(permission: Permission)
      extends CompositeViewRejection(
        s"The provided permission '${permission.value}' is not defined in the collection of allowed permissions."
      )

  /**
    * Rejection returned when attempting to create a view with an id that already exists.
    *
    * @param id the view id
    */
  final case class ViewAlreadyExists(id: Iri, project: ProjectRef)
      extends CompositeViewRejection(s"Composite view '$id' already exists in project '$project'.")

  /**
    * Rejection returned when a view that doesn't exist.
    *
    * @param id the view id
    */
  final case class ViewNotFound(id: Iri, project: ProjectRef)
      extends CompositeViewRejection(s"Composite view '$id' not found in project '$project'.")

  /**
    * Rejection returned when attempting to update/deprecate a view that is already deprecated.
    *
    * @param id the view id
    */
  final case class ViewIsDeprecated(id: Iri) extends CompositeViewRejection(s"Composite view '$id' is deprecated.")

  /**
    * Rejection returned when a subject intends to perform an operation on the current view, but either provided an
    * incorrect revision or a concurrent update won over this attempt.
    *
    * @param provided the provided revision
    * @param expected the expected revision
    */
  final case class IncorrectRev(provided: Long, expected: Long)
      extends CompositeViewRejection(
        s"Incorrect revision '$provided' provided, expected '$expected', the view may have been updated since last seen."
      )

  /**
    * Rejection returned when a subject intends to retrieve a view at a specific revision, but the provided revision
    * does not exist.
    *
    * @param provided the provided revision
    * @param current  the last known revision
    */
  final case class RevisionNotFound(provided: Long, current: Long)
      extends CompositeViewRejection(
        s"Revision requested '$provided' not found, last known revision is '$current'."
      )

  /**
    * Rejection returned when too many sources are specified.
    *
    * @param provided the number of sources specified
    * @param max      the maximum number of sources
    */
  final case class TooManySources(provided: Int, max: Int)
      extends CompositeViewRejection(
        s"$provided exceeds the maximum allowed number of sources($max)."
      )

  /**
    * Rejection returned when too many projections are specified.
    *
    * @param provided the number of projections specified
    * @param max      the maximum number of projections
    */
  final case class TooManyProjections(provided: Int, max: Int)
      extends CompositeViewRejection(
        s"$provided exceeds the maximum allowed number of projections($max)."
      )

  /**
    * Rejection signalling that a source is invalid.
    */
  sealed abstract class CompositeViewSourceRejection(reason: String) extends CompositeViewRejection(reason)

  /**
    * Rejection returned when the project for a [[CrossProjectSource]] does not exist.
    */
  final case class CrossProjectSourceProjectNotFound(crossProjectSource: CrossProjectSource)
      extends CompositeViewSourceRejection(
        s"Project ${crossProjectSource.project} does not exist for 'CrossProjectSource' ${crossProjectSource.id}"
      )

  /**
    * Rejection returned when the identities for a [[CrossProjectSource]] don't have access to target project.
    */
  final case class CrossProjectSourceForbidden(crossProjectSource: CrossProjectSource)(implicit val baseUri: BaseUri)
      extends CompositeViewSourceRejection(
        s"None of the identities  ${crossProjectSource.identities.map(_.id).mkString(",")} has permissions for project ${crossProjectSource.project}"
      )

  /**
    * Rejection returned when [[RemoteProjectSource]] is invalid.
    */
  final case class InvalidRemoteProjectSource(remoteProjectSource: RemoteProjectSource)
      extends CompositeViewSourceRejection(
        s"RemoteProjectSource ${remoteProjectSource.id} is invalid: either provided endpoint ${remoteProjectSource.endpoint} is invalid or there are insufficient permissions to access this endpoint. "
      )

  /**
    * Rejection signalling that a projection is invalid.
    */
  sealed abstract class CompositeViewProjectionRejection(reason: String) extends CompositeViewRejection(reason)

  /**
    * Rejection returned when the provided ElasticSearch mapping for an ElasticSearchProjection is invalid.
    */
  final case class InvalidElasticSearchProjectionPayload(details: Option[Json])
      extends CompositeViewProjectionRejection(
        "The provided ElasticSearch mapping value is invalid."
      )

  /**
    * Rejection returned when attempting to evaluate a command but the evaluation failed
    */
  final case class CompositeViewEvaluationError(err: EvaluationError)
      extends CompositeViewRejection("Unexpected evaluation error")
}
