package ch.epfl.bluebrain.nexus.delta.sourcing.stream

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoder
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Envelope, Label}
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem.SuccessElem
import monix.bio.Task
import shapeless.Typeable

class IntToString extends Pipe {
  override type In  = Int
  override type Out = String
  override def label: Label              = IntToString.label
  override def inType: Typeable[Int]     = Typeable[Int]
  override def outType: Typeable[String] = Typeable[String]

  override def apply(element: Envelope[Iri, SuccessElem[Int]]): Task[Envelope[Iri, Elem[String]]] =
    Task.pure(element.copy(value = SuccessElem(element.value.ctx, element.value.value.toString)))
}

object IntToString extends PipeDef {
  override type PipeType = IntToString
  override type Config   = Unit
  override def configType: Typeable[Config]          = Typeable[Unit]
  override def configDecoder: JsonLdDecoder[Unit]    = JsonLdDecoder[Unit]
  override def label: Label                          = Label.unsafe("int-to-string")
  override def withConfig(config: Unit): IntToString = new IntToString
}
