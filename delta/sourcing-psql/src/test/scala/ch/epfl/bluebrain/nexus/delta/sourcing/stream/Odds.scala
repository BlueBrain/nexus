package ch.epfl.bluebrain.nexus.delta.sourcing.stream
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoder
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Envelope, Label}
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem.{DroppedElem, SuccessElem}
import monix.bio.Task
import shapeless.Typeable

class Odds extends Pipe {
  override type In  = Int
  override type Out = Int
  override def label: Label           = Odds.label
  override def inType: Typeable[Int]  = Typeable[Int]
  override def outType: Typeable[Int] = Typeable[Int]

  override def apply(element: Envelope[Iri, SuccessElem[Int]]): Task[Envelope[Iri, Elem[Int]]] =
    if (element.value.value % 2 == 1) Task.pure(element)
    else Task.pure(element.copy(value = DroppedElem(element.value.ctx)))
}

object Odds extends PipeDef {
  override type PipeType = Odds
  override type Config   = Unit
  override def configType: Typeable[Config]       = Typeable[Unit]
  override def configDecoder: JsonLdDecoder[Unit] = JsonLdDecoder[Unit]
  override def label: Label                       = Label.unsafe("odds")
  override def withConfig(config: Unit): Odds     = new Odds
}
