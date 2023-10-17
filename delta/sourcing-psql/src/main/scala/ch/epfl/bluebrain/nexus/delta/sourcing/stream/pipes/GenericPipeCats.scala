package ch.epfl.bluebrain.nexus.delta.sourcing.stream.pipes

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoder
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem.SuccessElem
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Operation.PipeCatsEffect
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.{Elem, PipeDefCats, PipeRef}
import shapeless.Typeable

/**
  * A generic pipe instance backed by a fn.
  * @param label
  *   the pipe label
  * @param fn
  *   the fn to apply to each element
  * @tparam I
  *   the input element type
  * @tparam O
  *   the output element type
  */
class GenericPipeCats[I: Typeable, O: Typeable] private[stream] (
    label: Label,
    fn: SuccessElem[I] => IO[Elem[O]]
) extends PipeCatsEffect {
  override val ref: PipeRef = PipeRef(label)
  override type In  = I
  override type Out = O
  override def inType: Typeable[In]   = Typeable[In]
  override def outType: Typeable[Out] = Typeable[Out]

  override def apply(element: SuccessElem[In]): IO[Elem[Out]] = fn(element)
}

object GenericPipeCats {

  /**
    * Lifts the argument fn to a pipe and associated definition.
    * @param label
    *   the pipe/pipedef label
    * @param fn
    *   the fn to apply to elements
    * @tparam I
    *   the input element type
    * @tparam O
    *   the output element type
    */
  def apply[I: Typeable, O: Typeable](label: Label, fn: SuccessElem[I] => IO[Elem[O]]): PipeDefCats =
    new GenericPipeDef(label, fn)

  private class GenericPipeDef[I: Typeable, O: Typeable] private[stream] (
      label: Label,
      fn: SuccessElem[I] => IO[Elem[O]]
  ) extends PipeDefCats {
    override val ref: PipeRef = PipeRef(label)
    override type PipeType = GenericPipeCats[I, O]
    override type Config   = Unit
    override def configType: Typeable[Unit]         = Typeable[Unit]
    override def configDecoder: JsonLdDecoder[Unit] = JsonLdDecoder[Unit]

    override def withConfig(config: Unit): GenericPipeCats[I, O] = new GenericPipeCats[I, O](label, fn)
  }

}
