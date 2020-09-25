package ch.epfl.bluebrain.nexus.delta.sdk.model.search

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{nxv, schemas => nxvschemas}
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceRef.Latest
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Label, ResourceRef}

/**
  * Enumeration of the possible Search Parameters
  */
sealed trait SearchParams {
  def deprecated: Option[Boolean]
  def rev: Option[Long]
  def createdBy: Option[Subject]
  def updatedBy: Option[Subject]
  def types: Set[Iri]
  def schemas: Set[ResourceRef]
}

object SearchParams {

  /**
    * Search parameters to filter realm resources.
    *
    * @param deprecated the optional deprecation status of the realm resources
    * @param rev        the optional revision of the realm resources
    * @param createdBy  the optional subject who created the realm resource
    * @param updatedBy  the optional subject who updated the realm resource
    */
  final case class RealmSearchParams(
      deprecated: Option[Boolean] = None,
      rev: Option[Long] = None,
      createdBy: Option[Subject] = None,
      updatedBy: Option[Subject] = None
  ) extends SearchParams {
    override val types: Set[Iri]           = Set(nxv.Realm)
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
    * @param createdBy  the optional subject who created the organization resource
    * @param updatedBy  the optional subject who updated the resource
    */
  final case class OrganizationSearchParams(
      deprecated: Option[Boolean] = None,
      rev: Option[Long] = None,
      createdBy: Option[Subject] = None,
      updatedBy: Option[Subject] = None
  ) extends SearchParams {
    override val types: Set[Iri]           = Set(nxv.Organization)
    override val schemas: Set[ResourceRef] = Set(Latest(nxvschemas.organizations))
  }

  object OrganizationSearchParams {

    /**
      * An OrganizationSearchParams without any filters.
      */
    final val none: OrganizationSearchParams = OrganizationSearchParams()
  }

  /**
    * Search parameters to filter project resources.
    *
    * @param organization the optional parent organization of the project resources
    * @param deprecated   the optional deprecation status of the project resources
    * @param rev          the optional revision of the project resources
    * @param createdBy    the optional subject who created the project resource
    * @param updatedBy    the optional subject who updated the resource
    */
  final case class ProjectSearchParams(
      organization: Option[Label] = None,
      deprecated: Option[Boolean] = None,
      rev: Option[Long] = None,
      createdBy: Option[Subject] = None,
      updatedBy: Option[Subject] = None
  ) extends SearchParams {
    override val types: Set[Iri]           = Set(nxv.Project)
    override val schemas: Set[ResourceRef] = Set(Latest(nxvschemas.projects))
  }

  object ProjectSearchParams {

    /**
      * A ProjectSearchParams without any filters.
      */
    final val none: ProjectSearchParams = ProjectSearchParams()
  }

}
