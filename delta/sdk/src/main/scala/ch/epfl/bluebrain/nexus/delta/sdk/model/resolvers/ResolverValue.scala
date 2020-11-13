package ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers

import cats.data.NonEmptyList
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef

sealed trait ResolverValue extends Product with Serializable {

  /**
    * @return resolution priority when attempting to find a resource
    */
  def priority: Priority

  /**
    * @return the resolver type
    */
  def tpe: ResolverType

}

object ResolverValue {

  /**
    * Necessary values to use a cross-project resolver
    * @param priority      resolution priority when attempting to find a resource
    */
  final case class InProjectValue(priority: Priority) extends ResolverValue {

    /**
      * @return the resolver type
      */
    override def tpe: ResolverType = ResolverType.InProject
  }

  /**
    * Necessary values to use a cross-project resolver
    *
    * @param priority           resolution priority when attempting to find a resource
    * @param resourceTypes      the resource types that will be accessible through this resolver
    *                           if empty, no restriction on resource type will be applied
    * @param projects           references to projects where the resolver will attempt to access
    *                           resources
    * @param identityResolution identities allowed to use this resolver
    */
  final case class CrossProjectValue(
      priority: Priority,
      resourceTypes: Set[Iri],
      projects: NonEmptyList[ProjectRef],
      identityResolution: IdentityResolution
  ) extends ResolverValue {

    /**
      * @return the resolver type
      */
    override def tpe: ResolverType = ResolverType.CrossProject
  }

}
