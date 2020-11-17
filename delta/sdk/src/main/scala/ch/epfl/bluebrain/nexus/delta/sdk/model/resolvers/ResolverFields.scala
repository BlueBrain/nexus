package ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri

/**
  * Fields for a resolver
  * @param id                id of the resolver
  * @param value             additional fields to configure the resolver
  */
final case class ResolverFields(id: Option[Iri], value: ResolverValue)
