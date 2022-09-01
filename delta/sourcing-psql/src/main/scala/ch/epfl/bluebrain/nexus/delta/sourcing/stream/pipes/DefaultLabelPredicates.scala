package ch.epfl.bluebrain.nexus.delta.sourcing.stream.pipes

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{rdf, rdfs, schema, skos}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoder
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.PipeDef
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.pipes.SelectPredicates.SelectPredicatesConfig
import shapeless.Typeable

/**
  * Pipe implementation for UniformScopedState that transforms the resource graph keeping only the specific predicates
  * defined in [[DefaultLabelPredicates#defaultLabelPredicates]].
  */
object DefaultLabelPredicates extends PipeDef {
  override type PipeType = SelectPredicates
  override type Config   = Unit
  override def configType: Typeable[Config]         = Typeable[Unit]
  override def configDecoder: JsonLdDecoder[Config] = JsonLdDecoder[Unit]
  override def label: Label                         = Label.unsafe("default-label-predicates")

  override def withConfig(config: Unit): SelectPredicates = {
    val cfg = SelectPredicatesConfig(defaultLabelPredicates)
    new SelectPredicates(cfg)
  }

  val defaultLabelPredicates: Set[Iri] = Set(skos.prefLabel, rdf.tpe, rdfs.label, schema.name)
}
