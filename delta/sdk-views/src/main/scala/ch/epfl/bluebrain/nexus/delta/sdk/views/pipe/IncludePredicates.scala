package ch.epfl.bluebrain.nexus.delta.sdk.views.pipe

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Triple.{predicate, subject}
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary._
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
  private val defaultLabelPredicates = Set(skos.prefLabel, rdf.tpe, rdfs.label, schema.name)

  final private case class Config(predicates: Set[Iri]) {
    lazy val graphPredicates: Set[Node] = predicates.map(predicate)
  }

  val name = "includePredicates"

  val pipe: Pipe = {
    implicit val configDecoder: JsonLdDecoder[Config] = deriveJsonLdDecoder[Config]
    Pipe.withConfig(
      name,
      (config: Config, data: IndexingData) =>
        Task.some {
          val id       = subject(data.id)
          val newGraph = data.graph.filter { case (s, p, _) => s == id && config.graphPredicates.contains(p) }
          data.copy(
            graph = newGraph,
            types = newGraph.rootTypes
          )
        }
    )
  }

  private val predicatesKey = nxv + "predicates"

  def apply(set: Set[Iri]): PipeDef = {
    PipeDef.withConfig(
      name,
      set.foldLeft(ExpandedJsonLd.empty.copy(rootId = nxv + name)) { case (expanded, tpe) =>
        expanded.add(predicatesKey, tpe)
      }
    )
  }

  val defaultLabels: PipeDef = apply(defaultLabelPredicates)
}
