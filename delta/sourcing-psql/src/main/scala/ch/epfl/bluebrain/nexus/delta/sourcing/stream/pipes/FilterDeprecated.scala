package ch.epfl.bluebrain.nexus.delta.sourcing.stream.pipes

import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoder
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import ch.epfl.bluebrain.nexus.delta.sourcing.state.UniformScopedState
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem.SuccessElem
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.{Elem, Pipe, PipeDef}
import monix.bio.Task
import shapeless.Typeable

/**
  * Pipe implementation for UniformScopedState that filters deprecated resources.
  */
class FilterDeprecated extends Pipe {
  override type In  = UniformScopedState
  override type Out = UniformScopedState
  override def label: Label                          = FilterDeprecated.label
  override def inType: Typeable[UniformScopedState]  = Typeable[UniformScopedState]
  override def outType: Typeable[UniformScopedState] = Typeable[UniformScopedState]

  override def apply(element: SuccessElem[UniformScopedState]): Task[Elem[UniformScopedState]] =
    if (!element.value.deprecated) Task.pure(element)
    else Task.pure(element.dropped)

}

/**
  * Pipe implementation for UniformScopedState that filters deprecated resources.
  */
object FilterDeprecated extends PipeDef {
  override type PipeType = FilterDeprecated
  override type Config   = Unit
  override def configType: Typeable[Config]               = Typeable[Unit]
  override def configDecoder: JsonLdDecoder[Config]       = JsonLdDecoder[Unit]
  override def label: Label                               = Label.unsafe("filterDeprecated")
  override def withConfig(config: Unit): FilterDeprecated = new FilterDeprecated
}
