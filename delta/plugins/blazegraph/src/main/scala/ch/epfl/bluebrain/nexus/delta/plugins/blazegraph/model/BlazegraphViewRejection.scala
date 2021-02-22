package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model

import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.SparqlClientError
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.RdfError
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoderError
import ch.epfl.bluebrain.nexus.delta.sdk.Mapper
import ch.epfl.bluebrain.nexus.delta.sdk.error.ServiceError
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.JsonLdRejection
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.JsonLdRejection.UnexpectedId
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
    * @param id      the view id
    * @param project the project it belongs to
    */
  final case class ViewAlreadyExists(id: Iri, project: ProjectRef)
      extends BlazegraphViewRejection(s"Blazegraph view '$id' already exists in project '$project'.")

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
  final case class ViewIsDeprecated(id: Iri) extends BlazegraphViewRejection(s"Blazegraph view '$id' is deprecated.")

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

  /**
    * Rejection when attempting to decode an expanded JsonLD as an ElasticSearchViewValue.
    *
    * @param error the decoder error
    */
  final case class DecodingFailed(error: JsonLdDecoderError) extends BlazegraphViewRejection(error.getMessage)

  /**
    * Signals an error converting the source Json document to a JsonLD document.
    */
  final case class InvalidJsonLdFormat(id: Option[Iri], rdfError: RdfError)
      extends BlazegraphViewRejection(
        s"The provided Blazegraph view JSON document ${id.fold("")(id => s"with id '$id'")} cannot be interpreted as a JSON-LD document."
      )

  /**
    * Rejection returned when attempting to create an BlazegraphView where the passed id does not match the id on the
    * source json document.
    *
    * @param id       the view identifier
    * @param sourceId the view identifier in the source json document
    */
  final case class UnexpectedBlazegraphViewId(id: Iri, sourceId: Iri)
      extends BlazegraphViewRejection(
        s"The provided Blazegraph view '$id' does not match the id '$sourceId' in the source document."
      )

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
      expected: BlazegraphViewType
  ) extends BlazegraphViewRejection(
        s"Incorrect Blazegraph View '$id' type: '$provided' provided, expected '$expected'."
      )

  /**
    * Rejection returned when one of the provided view references for an AggregateBlazegraphView does not exist or
    * is deprecated.
    *
    * @param view the offending view reference
    */
  final case class InvalidViewReference(view: ViewRef)
      extends BlazegraphViewRejection(
        s"The Blazegraph view reference with id '${view.viewId}' in project '${view.project}' does not exist or is deprecated."
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
      extends BlazegraphViewRejection(s"Blazegraph view identifier '$id' cannot be expanded to an Iri.")

  /**
    * Rejection returned when attempting to query a BlazegraphView
    * and the caller does not have the right permissions defined in the view.
    */
  final case object AuthorizationFailed extends BlazegraphViewRejection(ServiceError.AuthorizationFailed.reason)

  /**
    * Signals a rejection caused when interacting with the blazegraph client
    */
  final case class WrappedBlazegraphClientError(error: SparqlClientError) extends BlazegraphViewRejection(error.reason)

  type AuthorizationFailed = AuthorizationFailed.type

  implicit val blazegraphViewsProjectRejectionMapper: Mapper[ProjectRejection, BlazegraphViewRejection] = {
    case ProjectRejection.WrappedOrganizationRejection(r) => WrappedOrganizationRejection(r)
    case value                                            => WrappedProjectRejection(value)
  }

  implicit val blazegraphViewOrgRejectionMapper: Mapper[OrganizationRejection, WrappedOrganizationRejection] =
    (value: OrganizationRejection) => WrappedOrganizationRejection(value)

  implicit val jsonLdRejectionMapper: Mapper[JsonLdRejection, BlazegraphViewRejection] = {
    case UnexpectedId(id, payloadIri)                      => UnexpectedBlazegraphViewId(id, payloadIri)
    case JsonLdRejection.InvalidJsonLdFormat(id, rdfError) => InvalidJsonLdFormat(id, rdfError)
    case JsonLdRejection.DecodingFailed(error)             => DecodingFailed(error)
  }
}
