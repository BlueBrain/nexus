package ch.epfl.bluebrain.nexus.cli

import cats.effect.implicits._
import cats.effect.{ConcurrentEffect, Sync, Timer}
import cats.implicits._
import fs2.concurrent.Queue

import scala.concurrent.duration.FiniteDuration
import scala.{Console => ScalaConsole}

trait Console[F[_]] {

  /**
    * Prints the passed ''line'' through the console to the standard output and then terminates the line.
    * The evaluation is delayed to the ''F'' effect type.
    */
  def println(line: String): F[Unit]

  /**
    * Prints the passed ''line'' through the console to the standard error and then terminates the line.
    * The evaluation is delayed to the ''F'' effect type.
    */
  def printlnErr(line: String): F[Unit]
}

object Console {

  /**
    * An implementation of a console backed up by the Scala Console and delay if the ''F'' effect context
    */
  final class LiveConsole[F[_]](implicit F: Sync[F]) extends Console[F] {
    override def println(line: String): F[Unit]    = F.delay(ScalaConsole.out.println(line))
    override def printlnErr(line: String): F[Unit] = F.delay(ScalaConsole.err.println(line))
  }

  /**
    * An implementation of a console with two queues to keep track of the effect of printing to console.
    *
    * @param stdQueue      the queue where the messages to print to the standard output will be added
    * @param errQueue      the queue where the messages to print to the standard error will be added
    * @param retentionTime the time to retain each messages in the queue. It is required in order to prevent
    *                      the queue from overflowing
    */
  private[cli] final class TestConsole[F[_]: Timer](
      stdQueue: Queue[F, String],
      errQueue: Queue[F, String],
      retentionTime: FiniteDuration
  )(implicit F: ConcurrentEffect[F])
      extends Console[F] {
    stdQueue.dequeue.metered(retentionTime).compile.drain.toIO.unsafeRunAsyncAndForget()
    errQueue.dequeue.metered(retentionTime).compile.drain.toIO.unsafeRunAsyncAndForget()
    override def println(line: String): F[Unit] =
      F.delay(ScalaConsole.out.println(line)) >> stdQueue.enqueue1(line) >> F.unit
    override def printlnErr(line: String): F[Unit] =
      F.delay(ScalaConsole.err.println(line)) >> errQueue.enqueue1(line) >> F.unit
  }
}
