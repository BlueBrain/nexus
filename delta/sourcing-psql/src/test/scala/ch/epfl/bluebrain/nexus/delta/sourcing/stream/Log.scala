package ch.epfl.bluebrain.nexus.delta.sourcing.stream

import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem.SuccessElem
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.pipes.LogElement
import fs2.concurrent.Queue
import monix.bio.Task
import shapeless.Typeable

object Log {
  def label: Label                                                    = Label.unsafe("log")
  def reference: PipeRef                                              = PipeRef(label)
  def apply[A: Typeable](queue: Queue[Task, SuccessElem[A]]): PipeDef =
    LogElement(label, queue.enqueue1)
}
