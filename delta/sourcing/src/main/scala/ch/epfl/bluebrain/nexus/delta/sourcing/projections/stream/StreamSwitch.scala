package ch.epfl.bluebrain.nexus.delta.sourcing.projections.stream

import fs2.concurrent.SignallingRef
import monix.bio.Task

import java.util.UUID
import scala.concurrent.duration._

/**
  * Switch identified by ann uuid allowing to stop a stream
  */
final class StreamSwitch[A] private[stream] (
    val uuid: UUID,
    val name: String,
    private[stream] val terminated: SignallingRef[Task, Boolean],
    private[stream] val stopMessage: SignallingRef[Task, A],
    private[stream] val interrupter: SignallingRef[Task, Boolean]
) {

  /**
    * Stops the stream without a ''message''. In this case, the ''stopMessage'' will be the initial.
    */
  def stop: Task[Unit] =
    interrupter.set(true) >> terminated.get.restartUntil(_ == true).timeout(3.seconds).void

  /**
    * Stops the stream with the passed ''message''
    */
  def stop(message: A): Task[Unit] =
    stopMessage.set(message) >> stop
}
