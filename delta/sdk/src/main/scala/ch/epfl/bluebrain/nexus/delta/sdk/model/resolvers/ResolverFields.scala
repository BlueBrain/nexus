package ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri

/**
  * Fields for a resolver
  * @param id                id of the resolver
  * @param priority          resolution priority when attempting to find a resource
  * @param value             additional fields to configure the resolver
  */
final case class ResolverFields(id: Option[Iri], priority: Priority, value: ResolverValue)
