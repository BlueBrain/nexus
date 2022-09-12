package ch.epfl.bluebrain.nexus.delta.sourcing.stream.pipes

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Triple.{predicate, subject}
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.ExpandedJsonLd
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoder
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.semiauto.deriveJsonLdDecoder
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import ch.epfl.bluebrain.nexus.delta.sourcing.state.UniformScopedState
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem.SuccessElem
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.pipes.SelectPredicates.SelectPredicatesConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.{Elem, Pipe, PipeDef}
import io.circe.syntax.EncoderOps
import io.circe.{Json, JsonObject}
import monix.bio.Task
import org.apache.jena.graph.Node
import shapeless.Typeable

/**
  * Pipe implementation for UniformScopedState that transforms the resource graph keeping only the specific predicates.
  */
class SelectPredicates(config: SelectPredicatesConfig) extends Pipe {
  override type In  = UniformScopedState
  override type Out = UniformScopedState
  override def label: Label                          = SelectPredicates.label
  override def inType: Typeable[UniformScopedState]  = Typeable[UniformScopedState]
  override def outType: Typeable[UniformScopedState] = Typeable[UniformScopedState]

  override def apply(element: SuccessElem[UniformScopedState]): Task[Elem[UniformScopedState]] = {
    val id       = subject(element.value.id)
    val newGraph = element.value.graph.filter { case (s, p, _) => s == id && config.nodeSet.contains(p) }
    val newState = element.value.copy(graph = newGraph, types = newGraph.rootTypes)
    Task.pure(element.copy(value = newState))
  }

}

/**
  * Pipe implementation for UniformScopedState that transforms the resource graph keeping only the specific predicates.
  */
object SelectPredicates extends PipeDef {
  override type PipeType = SelectPredicates
  override type Config   = SelectPredicatesConfig
  override def configType: Typeable[Config]                                 = Typeable[SelectPredicatesConfig]
  override def configDecoder: JsonLdDecoder[Config]                         = JsonLdDecoder[SelectPredicatesConfig]
  override def label: Label                                                 = Label.unsafe("selectPredicates")
  override def withConfig(config: SelectPredicatesConfig): SelectPredicates = new SelectPredicates(config)

  final case class SelectPredicatesConfig(predicates: Set[Iri]) {
    lazy val nodeSet: Set[Node]  = predicates.map(predicate)
    def toJsonLd: ExpandedJsonLd = ExpandedJsonLd(
      Seq(
        ExpandedJsonLd.unsafe(
          nxv + label.value,
          JsonObject(
            (nxv + "predicates").toString -> Json.arr(predicates.toList.map(iri => Json.obj("@id" -> iri.asJson)): _*)
          )
        )
      )
    )
  }
  object SelectPredicatesConfig                                 {
    implicit val selectPredicatesConfigJsonLdDecoder: JsonLdDecoder[SelectPredicatesConfig] = deriveJsonLdDecoder
  }
}
