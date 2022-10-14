package ch.epfl.bluebrain.nexus.delta.sourcing.stream.pipes

import ch.epfl.bluebrain.nexus.delta.rdf.graph.Graph
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoder
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import ch.epfl.bluebrain.nexus.delta.sourcing.state.GraphResource
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem.SuccessElem
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Operation.Pipe
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.{Elem, PipeDef}
import monix.bio.Task
import shapeless.Typeable

/**
  * Pipe implementation for UniformScopedState that drops the contents of the state metadata graph.
  */
class DiscardMetadata extends Pipe {
  override type In  = GraphResource
  override type Out = GraphResource
  override def label: Label                          = DiscardMetadata.label
  override def inType: Typeable[GraphResource]  = Typeable[GraphResource]
  override def outType: Typeable[GraphResource] = Typeable[GraphResource]

  override def apply(element: SuccessElem[GraphResource]): Task[Elem[GraphResource]] =
    Task.pure(element.map(state => state.copy(metadataGraph = Graph.empty(element.value.id))))

}

/**
  * Pipe implementation for UniformScopedState that drops the contents of the state metadata graph.
  */
object DiscardMetadata extends PipeDef {
  override type PipeType = DiscardMetadata
  override type Config   = Unit
  override def configType: Typeable[Config]              = Typeable[Unit]
  override def configDecoder: JsonLdDecoder[Config]      = JsonLdDecoder[Unit]
  override def label: Label                              = Label.unsafe("discardMetadata")
  override def withConfig(config: Unit): DiscardMetadata = new DiscardMetadata
}
