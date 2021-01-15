package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.Mapper
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.JsonLdRejection
import ch.epfl.bluebrain.nexus.delta.sdk.model.TagLabel
import ch.epfl.bluebrain.nexus.delta.sdk.model.organizations.OrganizationRejection
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{ProjectRef, ProjectRejection}

sealed abstract class BlazegraphViewRejection(val reason: String) extends Product with Serializable

object BlazegraphViewRejection {

  /**
    * Rejection returned when a subject intends to retrieve a view at a specific revision, but the provided revision
    * does not exist.
    *
    * @param provided the provided revision
    * @param current  the last known revision
    */
  final case class RevisionNotFound(provided: Long, current: Long)
      extends BlazegraphViewRejection(
        s"Revision requested '$provided' not found, last known revision is '$current'."
      )

  /**
    * Rejection returned when a subject intends to retrieve a view at a specific tag, but the provided tag
    * does not exist.
    *
    * @param tag the provided tag
    */
  final case class TagNotFound(tag: TagLabel) extends BlazegraphViewRejection(s"Tag requested '$tag' not found.")

  /**
    * Rejection returned when attempting to create a view with an id that already exists.
    *
    * @param id the view id
    */
  final case class ViewAlreadyExists(id: Iri) extends BlazegraphViewRejection(s"View '$id' already exists.")

  /**
    * Rejection returned when attempting to update a view that doesn't exist.
    *
    * @param id the view id
    * @param project the project it belongs to
    */
  final case class ViewNotFound(id: Iri, project: ProjectRef)
      extends BlazegraphViewRejection(s"Blazegraph view '$id' not found in project '$project'.")

  /**
    * Rejection returned when attempting to update/deprecate a view that is already deprecated.
    *
    * @param id the view id
    */
  final case class ViewIsDeprecated(id: Iri) extends BlazegraphViewRejection(s"View '$id' is deprecated.")

  /**
    * Rejection returned when a subject intends to perform an operation on the current view, but either provided an
    * incorrect revision or a concurrent update won over this attempt.
    *
    * @param provided the provided revision
    * @param expected the expected revision
    */
  final case class IncorrectRev(provided: Long, expected: Long)
      extends BlazegraphViewRejection(
        s"Incorrect revision '$provided' provided, expected '$expected', the view may have been updated since last seen."
      )

  /**
    * Signals a rejection caused when interacting with the projects API
    */
  final case class WrappedProjectRejection(rejection: ProjectRejection)
      extends BlazegraphViewRejection(rejection.reason)

  /**
    * Rejection returned when the associated organization is invalid
    *
    * @param rejection the rejection which occurred with the organization
    */
  final case class WrappedOrganizationRejection(rejection: OrganizationRejection)
      extends BlazegraphViewRejection(rejection.reason)

  final case class WrappedJsonLdRejection(rejection: JsonLdRejection) extends BlazegraphViewRejection(rejection.reason)

  /**
    * Signals a rejection caused by an attempt to create or update a Blazegraph view with a permission that is not
    * defined in the permission set singleton.
    *
    * @param permission the provided permission
    */
  final case class PermissionIsNotDefined(permission: Permission)
      extends BlazegraphViewRejection(
        s"The provided permission '${permission.value}' is not defined in the collection of allowed permissions."
      )

  /**
    * Rejection returned when attempting to update a Blazegraph view with a different value type.
    *
    * @param id the view id
    */
  final case class DifferentBlazegraphViewType(
      id: Iri,
      provided: BlazegraphViewType,
      current: BlazegraphViewType
  ) extends BlazegraphViewRejection(
        s"BlazegraphView '$id' is of type '$current' and can't be updated to be a '$provided'."
      )

  /**
    * Rejection returned when one of the provided view references for an AggregateBlazegraphView does not exist or
    * is deprecated.
    *
    * @param view the offending view reference
    */
  final case class InvalidViewReference(view: ViewRef)
      extends BlazegraphViewRejection(
        s"The view reference with id '${view.viewId}' in project '${view.project}' does not exist or is deprecated."
      )

  /**
    * Rejection returned when the returned state is the initial state after a BlazegraphViews.evaluation plus a BlazegraphViews.next
    * Note: This should never happen since the evaluation method already guarantees that the next function returns a current
    */
  final case class UnexpectedInitialState(id: Iri, project: ProjectRef)
      extends BlazegraphViewRejection(s"Unexpected initial state for Blazegraph view '$id' of project '$project'.")

  /**
    * Rejection returned when attempting to interact with a blazegraph view providing an id that cannot be resolved to an Iri.
    *
    * @param id the view identifier
    */
  final case class InvalidBlazegraphViewId(id: String)
      extends BlazegraphViewRejection(s"BlazegraphView identifier '$id' cannot be expanded to an Iri.")

  implicit val blazegraphViewsProjectRejectionMapper: Mapper[ProjectRejection, BlazegraphViewRejection] = {
    case ProjectRejection.WrappedOrganizationRejection(r) => WrappedOrganizationRejection(r)
    case value                                            => WrappedProjectRejection(value)
  }

  implicit val blazegraphViewOrgRejectionMapper: Mapper[OrganizationRejection, WrappedOrganizationRejection] =
    (value: OrganizationRejection) => WrappedOrganizationRejection(value)

  implicit val blazegraphViewJsonLdRejectionMapper: Mapper[JsonLdRejection, BlazegraphViewRejection] =
    WrappedJsonLdRejection(_)
}
