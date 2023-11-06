package ch.epfl.bluebrain.nexus.delta.sourcing.stream.pipes

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.ExpandedJsonLd
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoder
import ch.epfl.bluebrain.nexus.delta.sourcing.state.GraphResource
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem.SuccessElem
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Operation.Pipe
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.{Elem, PipeDef, PipeRef}
import shapeless.Typeable

/**
  * Pipe implementation that filters deprecated resources.
  */
class FilterDeprecated extends Pipe {
  override type In  = GraphResource
  override type Out = GraphResource
  override def ref: PipeRef                     = FilterDeprecated.ref
  override def inType: Typeable[GraphResource]  = Typeable[GraphResource]
  override def outType: Typeable[GraphResource] = Typeable[GraphResource]

  override def apply(element: SuccessElem[GraphResource]): IO[Elem[GraphResource]] =
    if (!element.value.deprecated) IO.pure(element)
    else IO.pure(element.dropped)

}

/**
  * Pipe implementation that filters deprecated resources.
  */
object FilterDeprecated extends PipeDef {
  override type PipeType = FilterDeprecated
  override type Config   = Unit
  override def configType: Typeable[Config]               = Typeable[Unit]
  override def configDecoder: JsonLdDecoder[Config]       = JsonLdDecoder[Unit]
  override def ref: PipeRef                               = PipeRef.unsafe("filterDeprecated")
  override def withConfig(config: Unit): FilterDeprecated = new FilterDeprecated

  /**
    * Returns the pipe ref and its empty config
    */
  def apply(): (PipeRef, ExpandedJsonLd) = ref -> ExpandedJsonLd.empty
}
