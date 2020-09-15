package ch.epfl.bluebrain.nexus.delta.sdk.model.search

import ch.epfl.bluebrain.nexus.delta.sdk.model.{Label, ResourceRef}
import org.apache.jena.iri.IRI

sealed trait SearchParams {
  def deprecated: Option[Boolean]
  def rev: Option[Long]
  def createdBy: Option[IRI]
  def updatedBy: Option[IRI]
  def types: Set[IRI]
  def schemas: Set[ResourceRef]
}

object SearchParams {

  /**
    * Search parameters to filter realm resources.
    *
   * @param deprecated the optional deprecation status of the realm resources
    * @param rev        the optional revision of the realm resources
    * @param createdBy  the optional identity id who created the realm resource
    * @param updatedBy  the optional identity id who updated the realm resource
    * @param types      the collection of types of the realm resources
    * @param schemas    the collection of schemas of the realm resources
    */
  final case class RealmSearchParams(
      deprecated: Option[Boolean] = None,
      rev: Option[Long] = None,
      createdBy: Option[IRI] = None,
      updatedBy: Option[IRI] = None,
      types: Set[IRI] = Set.empty,
      schemas: Set[ResourceRef] = Set.empty
  ) extends SearchParams

  object RealmSearchParams {
    final val none: RealmSearchParams = RealmSearchParams()
  }

  /**
    * Search parameters to filter organization resources.
    *
   * @param label      the optional label of the organization resources
    * @param deprecated the optional deprecation status of the organization resources
    * @param rev        the optional revision of the organization resources
    * @param createdBy  the optional identity id who created the organization resource
    * @param updatedBy  the optional identity id who updated the resource
    * @param types      the collection of types of the organization resources
    * @param schemas    the collection of schemas of the organization resources
    */
  final case class OrganizationSearchParams(
      label: Option[Label] = None,
      deprecated: Option[Boolean] = None,
      rev: Option[Long] = None,
      createdBy: Option[IRI] = None,
      updatedBy: Option[IRI] = None,
      types: Set[IRI] = Set.empty,
      schemas: Set[ResourceRef] = Set.empty
  ) extends SearchParams

  object OrganizationSearchParams {
    final val none: OrganizationSearchParams = OrganizationSearchParams()
  }

}
