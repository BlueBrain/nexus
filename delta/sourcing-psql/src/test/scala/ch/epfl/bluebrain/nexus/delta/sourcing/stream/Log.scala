package ch.epfl.bluebrain.nexus.delta.sourcing.stream

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoder
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Envelope, Label}
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem.SuccessElem
import fs2.concurrent.Queue
import monix.bio.Task
import shapeless.Typeable

class Log(queue: Queue[Task, SuccessElem[String]]) extends Pipe {
  override type In  = String
  override type Out = Unit
  override def label: Label             = Log.label
  override def inType: Typeable[String] = Typeable[String]
  override def outType: Typeable[Unit]  = Typeable[Unit]

  override def apply(element: Envelope[Iri, SuccessElem[String]]): Task[Envelope[Iri, Elem[Unit]]] =
    queue.enqueue1(element.value) >> Task.pure(element.copy(value = SuccessElem(element.value.ctx, ())))
}

object Log {
  def label: Label                                    = Label.unsafe("log")
  def reference: PipeRef                              = PipeRef(label)
  def apply(queue: Queue[Task, SuccessElem[String]]): LogDef = new LogDef(queue)

  class LogDef(queue: Queue[Task, SuccessElem[String]]) extends PipeDef {
    override type PipeType = Log
    override type Config   = Unit
    override def configType: Typeable[Config]       = Typeable[Unit]
    override def configDecoder: JsonLdDecoder[Unit] = JsonLdDecoder[Unit]
    override def label: Label                       = Log.label
    override def withConfig(config: Unit): Log      = new Log(queue)
  }
}
