package ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers

import cats.data.NonEmptyList
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity
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
}

object Resolver {

  /**
    * A resolver that looks only within its own project.
    */
  final case class InProjectResolver(id: Iri, project: ProjectRef, priority: Priority) extends Resolver

  /**
    * A resolver that can look across several projects.
    */
  final case class CrossProjectResolver(
      id: Iri,
      project: ProjectRef,
      priority: Priority,
      resourceTypes: Set[Iri],
      projects: NonEmptyList[ProjectRef],
      identities: Set[Identity]
  ) extends Resolver
}
