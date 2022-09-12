package ch.epfl.bluebrain.nexus.delta.sourcing.stream.pipes

import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoder
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem.SuccessElem
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.{Elem, Pipe, PipeDef}
import monix.bio.Task
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
class GenericPipe[I: Typeable, O: Typeable] private[stream] (
    override val label: Label,
    fn: SuccessElem[I] => Task[Elem[O]]
) extends Pipe {
  override type In  = I
  override type Out = O
  override def inType: Typeable[In]   = Typeable[In]
  override def outType: Typeable[Out] = Typeable[Out]

  override def apply(element: SuccessElem[In]): Task[Elem[Out]] = fn(element)
}

object GenericPipe {

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
  def apply[I: Typeable, O: Typeable](label: Label, fn: SuccessElem[I] => Task[Elem[O]]): PipeDef =
    new GenericPipeDef(label, fn)

  private class GenericPipeDef[I: Typeable, O: Typeable] private[stream] (
      override val label: Label,
      fn: SuccessElem[I] => Task[Elem[O]]
  ) extends PipeDef {
    override type PipeType = GenericPipe[I, O]
    override type Config   = Unit
    override def configType: Typeable[Unit]         = Typeable[Unit]
    override def configDecoder: JsonLdDecoder[Unit] = JsonLdDecoder[Unit]

    override def withConfig(config: Unit): GenericPipe[I, O] = new GenericPipe[I, O](label, fn)
  }

}
