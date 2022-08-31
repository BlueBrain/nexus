package ch.epfl.bluebrain.nexus.delta.sourcing.stream

import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoder
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem.SuccessElem
import fs2.concurrent.Queue
import monix.bio.Task
import shapeless.Typeable

class Log[A: Typeable](queue: Queue[Task, SuccessElem[A]]) extends Pipe {
  override type In  = A
  override type Out = Unit
  override def label: Label            = Log.label
  override def inType: Typeable[A]     = Typeable[A]
  override def outType: Typeable[Unit] = Typeable[Unit]

  override def apply(element: SuccessElem[A]): Task[Elem[Unit]] =
    queue.enqueue1(element) >> Task.pure(element.success(()))
}

object Log {
  def label: Label                                                      = Label.unsafe("log")
  def reference: PipeRef                                                = PipeRef(label)
  def apply[A: Typeable](queue: Queue[Task, SuccessElem[A]]): LogDef[A] = new LogDef(queue)

  class LogDef[A: Typeable](queue: Queue[Task, SuccessElem[A]]) extends PipeDef {
    override type PipeType = Log[A]
    override type Config   = Unit
    override def configType: Typeable[Config]       = Typeable[Unit]
    override def configDecoder: JsonLdDecoder[Unit] = JsonLdDecoder[Unit]
    override def label: Label                       = Log.label
    override def withConfig(config: Unit): Log[A]   = new Log(queue)
  }
}
