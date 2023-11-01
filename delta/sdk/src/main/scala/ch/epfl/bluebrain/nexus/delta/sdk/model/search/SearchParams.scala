package ch.epfl.bluebrain.nexus.delta.sdk.model.search

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{nxv, schemas => nxvschemas}
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceF
import ch.epfl.bluebrain.nexus.delta.sdk.organizations.model.Organization
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.Project
import ch.epfl.bluebrain.nexus.delta.sdk.realms.model.Realm
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model.Resolver
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ResourceRef.Latest
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef, ResourceRef}

/**
  * Enumeration of the possible Search Parameters
  */
trait SearchParams[A] {
  def deprecated: Option[Boolean]
  def rev: Option[Int]
  def createdBy: Option[Subject]
  def updatedBy: Option[Subject]
  def types: Set[Iri]
  def schema: Option[ResourceRef]
  def filter: A => IO[Boolean]

  /**
    * Checks whether a ''resource'' matches the current [[SearchParams]].
    *
    * @param resource
    *   a resource
    */
  def matches(resource: ResourceF[A]): IO[Boolean] =
    IO
      .pure(
        rev.forall(_ == resource.rev) &&
          deprecated.forall(_ == resource.deprecated) &&
          createdBy.forall(_ == resource.createdBy) &&
          updatedBy.forall(_ == resource.updatedBy) &&
          schema.forall(_ == resource.schema) &&
          types.subsetOf(resource.types)
      )
      .flatMap(b => filter(resource.value).map(_ && b))
}

object SearchParams {

  /**
    * Search parameters to filter realm resources.
    *
    * @param issuer
    *   the optional issuer of the realm resource
    * @param deprecated
    *   the optional deprecation status of the realm resources
    * @param rev
    *   the optional revision of the realm resources
    * @param createdBy
    *   the optional subject who created the realm resource
    * @param updatedBy
    *   the optional subject who updated the realm resource
    * @param filter
    *   the additional filter to select realms
    */
  final case class RealmSearchParams(
      issuer: Option[String] = None,
      deprecated: Option[Boolean] = None,
      rev: Option[Int] = None,
      createdBy: Option[Subject] = None,
      updatedBy: Option[Subject] = None,
      filter: Realm => IO[Boolean] = _ => IO.pure(true)
  ) extends SearchParams[Realm] {
    override val types: Set[Iri]             = Set(nxv.Realm)
    override val schema: Option[ResourceRef] = Some(Latest(nxvschemas.realms))

    override def matches(resource: ResourceF[Realm]): IO[Boolean] =
      super.matches(resource).map(_ && issuer.forall(_ == resource.value.issuer))
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
    * @param deprecated
    *   the optional deprecation status of the organization resources
    * @param rev
    *   the optional revision of the organization resources
    * @param createdBy
    *   the optional subject who created the organization resource
    * @param updatedBy
    *   the optional subject who updated the resource
    * @param label
    *   the optional organization label (matches with a contains)
    * @param filter
    *   the additional filter to select organizations
    */
  final case class OrganizationSearchParams(
      deprecated: Option[Boolean] = None,
      rev: Option[Int] = None,
      createdBy: Option[Subject] = None,
      updatedBy: Option[Subject] = None,
      label: Option[String] = None,
      filter: Organization => IO[Boolean]
  ) extends SearchParams[Organization] {
    override val types: Set[Iri]                                         = Set(nxv.Organization)
    override val schema: Option[ResourceRef]                             = Some(Latest(nxvschemas.organizations))
    override def matches(resource: ResourceF[Organization]): IO[Boolean] =
      super
        .matches(resource)
        .map(_ && label.forall(lb => resource.value.label.value.toLowerCase.contains(lb.toLowerCase.trim)))
  }

  /**
    * Search parameters to filter project resources.
    *
    * @param organization
    *   the optional parent organization of the project resources
    * @param deprecated
    *   the optional deprecation status of the project resources
    * @param rev
    *   the optional revision of the project resources
    * @param createdBy
    *   the optional subject who created the project resource
    * @param updatedBy
    *   the optional subject who updated the resource
    * @param label
    *   the optional project label (matches with a contains)
    * @param filter
    *   the additional filter to select projects
    */
  final case class ProjectSearchParams(
      organization: Option[Label] = None,
      deprecated: Option[Boolean] = None,
      rev: Option[Int] = None,
      createdBy: Option[Subject] = None,
      updatedBy: Option[Subject] = None,
      label: Option[String] = None,
      filter: Project => IO[Boolean]
  ) extends SearchParams[Project] {
    override val types: Set[Iri]             = Set(nxv.Project)
    override val schema: Option[ResourceRef] = Some(Latest(nxvschemas.projects))

    override def matches(resource: ResourceF[Project]): IO[Boolean] =
      super
        .matches(resource)
        .map(
          _ &&
            organization.forall(_ == resource.value.organizationLabel) &&
            label.forall(lb => resource.value.label.value.toLowerCase.contains(lb.toLowerCase.trim))
        )
  }

  /**
    * Search parameters for resolvers
    *
    * @param project
    *   the option project of the resolver resources
    * @param deprecated
    *   the optional deprecation status of resolver project resources
    * @param rev
    *   the optional revision of the resolver resources
    * @param createdBy
    *   the optional subject who created the resolver resource
    * @param updatedBy
    *   the optional subject who updated the resolver
    * @param types
    *   the types the resolver should contain
    * @param filter
    *   the additional filter to select resolvers
    */
  final case class ResolverSearchParams(
      project: Option[ProjectRef] = None,
      deprecated: Option[Boolean] = None,
      rev: Option[Int] = None,
      createdBy: Option[Subject] = None,
      updatedBy: Option[Subject] = None,
      types: Set[Iri] = Set(nxv.Resolver),
      filter: Resolver => IO[Boolean]
  ) extends SearchParams[Resolver] {
    override val schema: Option[ResourceRef] = Some(Latest(nxvschemas.resolvers))

    override def matches(resource: ResourceF[Resolver]): IO[Boolean] =
      super.matches(resource).map(_ && project.forall(_ == resource.value.project))

  }

}
