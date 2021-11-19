package ch.epfl.bluebrain.nexus.delta.sdk.views.pipe

import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{rdf, rdfs, schema, skos}
import ch.epfl.bluebrain.nexus.delta.sdk.views.pipe.SelectPredicates.SelectPredicatesConfig

object DefaultLabelPredicates {

  // TODO make this configurable
  private val defaultLabelPredicates = Set(skos.prefLabel, rdf.tpe, rdfs.label, schema.name)

  val name = "defaultLabelPredicates"

  val pipe: Pipe = Pipe.withoutConfig(
    name,
    SelectPredicates.pipe
      .run(SelectPredicatesConfig(defaultLabelPredicates).asInstanceOf[SelectPredicates.pipe.Config], _)
  )

  def apply(): PipeDef = PipeDef.noConfig(name)

}
