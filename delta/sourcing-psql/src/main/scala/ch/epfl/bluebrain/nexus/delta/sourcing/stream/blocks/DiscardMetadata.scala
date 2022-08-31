package ch.epfl.bluebrain.nexus.delta.sourcing.stream.blocks

import ch.epfl.bluebrain.nexus.delta.rdf.graph.Graph
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoder
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import ch.epfl.bluebrain.nexus.delta.sourcing.state.UniformScopedState
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem.SuccessElem
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.{Elem, Pipe, PipeDef}
import monix.bio.Task
import shapeless.Typeable

/**
  * Pipe implementation for UniformScopedState that drops the contents of the state metadata graph.
  */
class DiscardMetadata extends Pipe {
  override type In  = UniformScopedState
  override type Out = UniformScopedState
  override def label: Label                          = DiscardMetadata.label
  override def inType: Typeable[UniformScopedState]  = Typeable[UniformScopedState]
  override def outType: Typeable[UniformScopedState] = Typeable[UniformScopedState]

  override def apply(element: SuccessElem[UniformScopedState]): Task[Elem[UniformScopedState]] =
    Task.pure(element.map(state => state.copy(metadataGraph = Graph.empty(element.id))))

}

/**
  * Pipe implementation for UniformScopedState that drops the contents of the state metadata graph.
  */
object DiscardMetadata extends PipeDef {
  override type PipeType = DiscardMetadata
  override type Config   = Unit
  override def configType: Typeable[Config]              = Typeable[Unit]
  override def configDecoder: JsonLdDecoder[Config]      = JsonLdDecoder[Unit]
  override def label: Label                              = Label.unsafe("discard-metadata")
  override def withConfig(config: Unit): DiscardMetadata = new DiscardMetadata
}
