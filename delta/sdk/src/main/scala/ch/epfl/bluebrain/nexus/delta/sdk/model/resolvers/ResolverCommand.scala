package ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers

import cats.data.NonEmptyList
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef

/**
  * Enumeration of Resolver command types.
  */
sealed trait ResolverCommand extends Product with Serializable {

  /**
    * @return the project where the resolver belongs to
    */
  def project: ProjectRef

  /**
    * @return the resolver identifier
    */
  def id: Iri
}

object ResolverCommand {

  /**
    * Command to create a resolver
    */
  sealed trait CreateResolver extends ResolverCommand

  /**
    * Command to update a resolver
    */
  sealed trait UpdateResolver extends ResolverCommand {

    /**
      * @return the last known revision of the resolver
      */
    def rev: Long
  }

  /**
    * Command to create a new InProjectResolver
    * @param id        the resolver identifier
    * @param project   the project the resolver belongs to
    * @param priority  resolution priority when attempting to find a resource
    */
  final case class CreateInProjectResolver(id: Iri, project: ProjectRef, priority: Priority) extends CreateResolver

  /**
    * Command to update an existing InProjectResolver
    * @param id        the resolver identifier
    * @param project   the project the resolver belongs to
    * @param priority  resolution priority when attempting to find a resource
    * @param rev       the last known revision of the resolver
    */
  final case class UpdateInProjectResolver(id: Iri, project: ProjectRef, priority: Priority, rev: Long)
      extends UpdateResolver

  /**
    * Command to create a new CrossProjectResolver
    * @param id            the resolver identifier
    * @param project       the project the resolver belongs to
    * @param resourceTypes the resource types that will be accessible through this resolver
    *                      if empty, no restriction on resource type will be applied
    * @param projects      references to projects where the resolver will attempt to access
    *                      resources
    * @param identities    identities allowed to use this resolver
    * @param priority      resolution priority when attempting to find a resource
    */
  final case class CreateCrossProjectResolver(
      id: Iri,
      project: ProjectRef,
      resourceTypes: Set[Iri],
      projects: NonEmptyList[ProjectRef],
      identities: Set[Identity],
      priority: Priority
  ) extends CreateResolver

  /**
    * Command to update an existing CrossProjectResolver
    * @param id            the resolver identifier
    * @param project       the project the resolver belongs to
    * @param resourceTypes the resource types that will be accessible through this resolver
    *                      if empty, no restriction on resource type will be applied
    * @param projects      references to projects where the resolver will attempt to access
    *                      resources
    * @param identities    identities allowed to use this resolver
    * @param priority      resolution priority when attempting to find a resource
    * @param rev           the last known revision of the resolver
    */
  final case class UpdateCrossProjectResolver(
      id: Iri,
      project: ProjectRef,
      resourceTypes: Set[Iri],
      projects: NonEmptyList[ProjectRef],
      identities: Set[Identity],
      priority: Priority,
      rev: Long
  ) extends UpdateResolver

  /**
    * Command to deprecate a resolver
    * @param id      the resolver identifier
    * @param project the project the resolver belongs to
    * @param rev     the last known revision of the resolver
    */
  final case class DeprecateResolver(id: Iri, project: ProjectRef, rev: Long) extends ResolverCommand

}
