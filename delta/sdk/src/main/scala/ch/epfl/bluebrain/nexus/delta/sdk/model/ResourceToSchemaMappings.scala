package ch.epfl.bluebrain.nexus.delta.sdk.model

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label

/**
  * A mapping from resource segments to their schema
  */
final case class ResourceToSchemaMappings private (value: Map[Label, Iri]) {
  def +(that: ResourceToSchemaMappings): ResourceToSchemaMappings = ResourceToSchemaMappings(value ++ that.value)
}

object ResourceToSchemaMappings {
  val empty: ResourceToSchemaMappings = ResourceToSchemaMappings()

  def apply(values: (Label, Iri)*): ResourceToSchemaMappings = ResourceToSchemaMappings(values.toMap)
}
