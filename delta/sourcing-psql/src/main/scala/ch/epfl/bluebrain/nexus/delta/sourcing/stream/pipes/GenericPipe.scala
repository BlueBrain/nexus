package ch.epfl.bluebrain.nexus.delta.sourcing.stream.pipes

import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoder
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.{Elem, Pipe, PipeDef}
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem.SuccessElem
import monix.bio.Task
import shapeless.Typeable

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

  def apply[I: Typeable, O: Typeable](label: Label, fn: SuccessElem[I] => Task[Elem[O]]): PipeDef =
    new GenericPipeDef(label, fn)

  class GenericPipeDef[I: Typeable, O: Typeable] private[stream] (
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
