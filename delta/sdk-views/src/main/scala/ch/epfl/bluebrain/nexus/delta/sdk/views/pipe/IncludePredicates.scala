package ch.epfl.bluebrain.nexus.delta.sdk.views.pipe

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Triple.{predicate, subject}
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{nxv, rdf, rdfs, skos}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.ExpandedJsonLd
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoder
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.semiauto.deriveJsonLdDecoder
import ch.epfl.bluebrain.nexus.delta.sdk.views.model.IndexingData
import monix.bio.Task
import org.apache.jena.graph.Node

/**
  * Keeps only predicates matching the provided `Iri` list
  */
object IncludePredicates {

  // TODO make this configurable
  private val defaultLabelPredicates = Set(skos.prefLabel, rdf.tpe, rdfs.label, Vocabulary.schema.name)

  final private case class Config(predicates: Set[Iri]) {
    lazy val graphPredicates: Set[Node] = predicates.map(predicate)
  }

  val value: Pipe = {
    implicit val configDecoder: JsonLdDecoder[Config] = deriveJsonLdDecoder[Config]
    Pipe.withConfig(
      "includePredicates",
      (config: Config, data: IndexingData) =>
        Task.some {
          val id = subject(data.id)
          data.copy(graph = data.graph.filter { case (s, p, _) => s == id && config.graphPredicates.contains(p) })
        }
    )
  }

  private val predicatesKey = nxv + "predicates"

  def definition(set: Set[Iri]): PipeDef = {
    PipeDef.withConfig(
      "includePredicates",
      set.foldLeft(ExpandedJsonLd.empty) { case (expanded, tpe) =>
        expanded.add(predicatesKey, tpe)
      }
    )
  }

  val defaultLabelPredicatesDef: PipeDef = definition(defaultLabelPredicates)
}
