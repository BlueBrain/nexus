package ch.epfl.bluebrain.nexus.delta.sourcing.stream
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoder
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem.SuccessElem
import monix.bio.Task
import shapeless.Typeable

class Odds extends Pipe {
  override type In  = Int
  override type Out = Int
  override def label: Label           = Odds.label
  override def inType: Typeable[Int]  = Typeable[Int]
  override def outType: Typeable[Int] = Typeable[Int]

  override def apply(element: SuccessElem[Int]): Task[Elem[Int]] =
    if (element.value % 2 == 1) Task.pure(element)
    else Task.pure(element.dropped)
}

object Odds extends PipeDef {
  override type PipeType = Odds
  override type Config   = Unit
  override def configType: Typeable[Config]       = Typeable[Unit]
  override def configDecoder: JsonLdDecoder[Unit] = JsonLdDecoder[Unit]
  override def label: Label                       = Label.unsafe("odds")
  override def withConfig(config: Unit): Odds     = new Odds
}
