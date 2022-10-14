package ch.epfl.bluebrain.nexus.delta.sourcing.stream

import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoder
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem.SuccessElem
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Operation.Pipe
import monix.bio.Task
import shapeless.Typeable

class Evens extends Pipe {
  override type In  = Int
  override type Out = Int
  override def label: Label           = Evens.label
  override def inType: Typeable[Int]  = Typeable[Int]
  override def outType: Typeable[Int] = Typeable[Int]

  override def apply(element: SuccessElem[Int]): Task[Elem[Int]] = {
    if (element.value % 2 == 0) Task.pure(element)
    else Task.pure(element.dropped)
  }
}

object Evens extends PipeDef {
  override type PipeType = Evens
  override type Config   = Unit
  override def configType: Typeable[Config]       = Typeable[Unit]
  override def configDecoder: JsonLdDecoder[Unit] = JsonLdDecoder[Unit]
  override def label: Label                       = Label.unsafe("evens")
  override def withConfig(config: Unit): Evens    = new Evens
}
