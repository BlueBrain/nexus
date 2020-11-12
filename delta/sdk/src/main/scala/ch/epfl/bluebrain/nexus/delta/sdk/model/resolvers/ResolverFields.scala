package ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers

import cats.data.{NonEmptyList, NonEmptySet}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef

/**
  * Payload for a resolver creation/update request
  */
trait ResolverFields extends Product with Serializable {

  /**
    * @return id of the resolver
    */
  def id: Option[Iri]

  /**
    * @return resolution priority when attempting to find a resource
    */
  def priority: Priority

}

object ResolverFields {

  /**
    * Fields for an ''InProjectResolver''
    *
    * @param id       id of the resolver
    * @param priority resolution priority when attempting to find a resource
    */
  final case class InProjectResolverFields(id: Option[Iri], priority: Priority) extends ResolverFields

  /**
    * @param id            id of the resolver
    * @param resourceTypes the resource types that will be accessible through this resolver
    *                      if empty, no restriction on resource type will be applied
    * @param projects      references to projects where the resolver will attempt to access
    *                      resources
    * @param identities
    * @param priority     resolution priority when attempting to find a resource
    */
  final case class CrossProjectResolerFields(
      id: Option[Iri],
      resourceTypes: Set[Iri],
      projects: NonEmptyList[ProjectRef],
      identities: NonEmptySet[Identity],
      priority: Priority
  )

}
