package ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers

import cats.data.NonEmptyList
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.model.Label
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef

/**
  * Enumeration of Resolver types.
  */
sealed trait Resolver extends Product with Serializable {

  /**
    * @return the resolver id
    */
  def id: Iri

  /**
    * @return a reference to the project that the resolver belongs to
    */
  def project: ProjectRef

  /**
    * @return the resolver priority
    */
  def priority: Priority

  /**
    * @return the collection of tag aliases
    */
  def tags: Map[Label, Long]
}

object Resolver {

  /**
    * A resolver that looks only within its own project.
    */
  final case class InProjectResolver(id: Iri, project: ProjectRef, priority: Priority, tags: Map[Label, Long])
      extends Resolver

  /**
    * A resolver that can look across several projects.
    */
  final case class CrossProjectResolver(
      id: Iri,
      project: ProjectRef,
      resourceTypes: Set[Iri],
      projects: NonEmptyList[ProjectRef],
      identityResolution: IdentityResolution,
      priority: Priority,
      tags: Map[Label, Long]
  ) extends Resolver
}
