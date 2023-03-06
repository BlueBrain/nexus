package ch.epfl.bluebrain.nexus.delta.sourcing.stream.pipes

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Triple.{predicate, subject}
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.ExpandedJsonLd
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoder
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.semiauto.deriveDefaultJsonLdDecoder
import ch.epfl.bluebrain.nexus.delta.sourcing.state.GraphResource
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem.SuccessElem
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Operation.Pipe
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.pipes.SelectPredicates.SelectPredicatesConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.{Elem, PipeDef, PipeRef}
import io.circe.syntax.EncoderOps
import io.circe.{Json, JsonObject}
import monix.bio.Task
import org.apache.jena.graph.Node
import shapeless.Typeable

/**
  * Pipe implementation that transforms the resource graph keeping only the specific predicates.
  */
class SelectPredicates(config: SelectPredicatesConfig) extends Pipe {
  override type In  = GraphResource
  override type Out = GraphResource
  override def ref: PipeRef                     = SelectPredicates.ref
  override def inType: Typeable[GraphResource]  = Typeable[GraphResource]
  override def outType: Typeable[GraphResource] = Typeable[GraphResource]

  override def apply(element: SuccessElem[GraphResource]): Task[Elem[GraphResource]] = Task.pure {
    if (config.forwardTypes.exists { p => p.exists(element.value.types.contains) }) {
      element
    } else {
      val id       = subject(element.value.id)
      val newGraph = element.value.graph.filter { case (s, p, _) => s == id && config.nodeSet.contains(p) }
      val newState = element.value.copy(graph = newGraph, types = newGraph.rootTypes)
      element.copy(value = newState)
    }
  }

}

/**
  * Pipe implementation that transforms the resource graph keeping only the specific predicates.
  */
object SelectPredicates extends PipeDef {
  override type PipeType = SelectPredicates
  override type Config   = SelectPredicatesConfig
  override def configType: Typeable[Config]                                 = Typeable[SelectPredicatesConfig]
  override def configDecoder: JsonLdDecoder[Config]                         = JsonLdDecoder[SelectPredicatesConfig]
  override def ref: PipeRef                                                 = PipeRef.unsafe("selectPredicates")
  override def withConfig(config: SelectPredicatesConfig): SelectPredicates = new SelectPredicates(config)

  /**
    * Configuration of the [[SelectPredicates]]
    * @param forwardTypes
    *   types that must keep all their predicates
    * @param predicates
    *   predicates to retain for the other types
    */
  final case class SelectPredicatesConfig(forwardTypes: Option[Set[Iri]], predicates: Set[Iri]) {
    lazy val nodeSet: Set[Node]  = predicates.map(predicate)
    def toJsonLd: ExpandedJsonLd = ExpandedJsonLd(
      Seq(
        ExpandedJsonLd.unsafe(
          nxv + ref.toString,
          JsonObject(
            (nxv + "pass").toString       -> forwardTypes.fold(Json.Null) { pass => toJson(pass) },
            (nxv + "predicates").toString -> toJson(predicates)
          )
        )
      )
    )

    private def toJson(set: Set[Iri]) = Json.fromValues(set.map(iri => Json.obj("@id" -> iri.asJson)))
  }
  object SelectPredicatesConfig                                                                 {
    implicit val selectPredicatesConfigJsonLdDecoder: JsonLdDecoder[SelectPredicatesConfig] = deriveDefaultJsonLdDecoder
  }
}
