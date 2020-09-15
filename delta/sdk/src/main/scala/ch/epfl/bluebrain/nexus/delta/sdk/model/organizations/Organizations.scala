package ch.epfl.bluebrain.nexus.delta.sdk.model.organizations

import java.util.UUID

import ch.epfl.bluebrain.nexus.delta.sdk.OrganizationResource
import ch.epfl.bluebrain.nexus.delta.sdk.model.Label
import ch.epfl.bluebrain.nexus.delta.sdk.model.organizations.OrganizationRejection.RevisionNotFound
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.FromPagination
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchParams.OrganizationSearchParams
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults.UnscoredSearchResults
import monix.bio.{IO, Task}

/**
  * Operations pertaining to managing organizations.
  */
trait Organizations {

  /**
    * Creates a new organization.
    *
   * @param organization the organization to create
    */
  def create(organization: Organization): IO[OrganizationRejection, OrganizationResource]

  /**
    * Updates an existing organization description.
    *
   * @param label       label of the organization to update
    * @param description the description of the organization to be updated
    * @param rev         the latest known revision
    */
  def update(label: Label, description: Option[String], rev: Long): IO[OrganizationRejection, OrganizationResource]

  /**
    * Deprecate an organization.
    *
   * @param label  label of the organization to deprecate
    * @param rev    latest known revision
    */
  def deprecate(label: Label, rev: Long): IO[OrganizationRejection, OrganizationResource]

  /**
    * Fetch an organization at the current revision by label.
    *
   * @param label the organization label
    * @return the organization in a Resource representation, None otherwise
    */
  def fetch(label: Label): Task[Option[OrganizationResource]]

  /**
    * Fetch an organization at the passed revision by label.
    *
   * @param label the organization label
    * @param rev   the organization revision
    * @return the organization in a Resource representation, None otherwise
    */
  def fetchAt(label: Label, rev: Long): IO[RevisionNotFound, Option[OrganizationResource]]

  /**
    * Fetch an organization at the current revision by uuid.
    *
   * @param  uuid the organization uuid
    * @return the organization in a Resource representation, None otherwise
    */
  def fetch(uuid: UUID): Task[Option[OrganizationResource]]

  /**
    * Fetch an organization at the passed revision by uuid.
    *
   * @param  uuid the organization uuid
    * @param rev   the organization revision
    * @return the organization in a Resource representation, None otherwise
    */
  def fetchAt(uuid: UUID, rev: Long): IO[RevisionNotFound, Option[OrganizationResource]]

  /**
    * Lists all organizations.
    *
   * @param pagination the pagination settings
    * @param params     filter parameters of the organization
    * @return a paginated results list
    */
  def list(
      pagination: FromPagination,
      params: OrganizationSearchParams = OrganizationSearchParams.none
  ): Task[UnscoredSearchResults[OrganizationResource]]

}
