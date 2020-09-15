package ch.epfl.bluebrain.nexus.delta.sdk.model.search

import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{nxv, schemas => nxvschemas}
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceRef.Latest
import org.apache.jena.iri.IRI

/**
  * Enumeration of the possible Search Parameters
  */
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
    */
  final case class RealmSearchParams(
      deprecated: Option[Boolean] = None,
      rev: Option[Long] = None,
      createdBy: Option[IRI] = None,
      updatedBy: Option[IRI] = None
  ) extends SearchParams {
    override val types: Set[IRI]           = Set(nxv.Realm)
    override val schemas: Set[ResourceRef] = Set(Latest(nxvschemas.realms))
  }

  object RealmSearchParams {

    /**
      * A RealmSearchParams without any filters
      */
    final val none: RealmSearchParams = RealmSearchParams()
  }

  /**
    * Search parameters to filter organization resources.
    *
    * @param deprecated the optional deprecation status of the organization resources
    * @param rev        the optional revision of the organization resources
    * @param createdBy  the optional identity id who created the organization resource
    * @param updatedBy  the optional identity id who updated the resource
    */
  final case class OrganizationSearchParams(
      deprecated: Option[Boolean] = None,
      rev: Option[Long] = None,
      createdBy: Option[IRI] = None,
      updatedBy: Option[IRI] = None
  ) extends SearchParams {
    override val types: Set[IRI]           = Set(nxv.Organization)
    override val schemas: Set[ResourceRef] = Set(Latest(nxvschemas.organizations))
  }

  object OrganizationSearchParams {

    /**
      * An OrganizationSearchParams without any filters
      */
    final val none: OrganizationSearchParams = OrganizationSearchParams()
  }

}
