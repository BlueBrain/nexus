package ch.epfl.bluebrain.nexus.cli.dummies

import cats.implicits._
import cats.effect.Concurrent
import ch.epfl.bluebrain.nexus.cli.Console
import fs2.concurrent.Queue

/**
  * An implementation of a console with two queues to keep track of the effect of printing to console.
  *
  * @param stdQueue the queue where the messages to print to the standard output will be added
  * @param errQueue the queue where the messages to print to the standard error will be added
  */
final class TestConsole[F[_]](
    val stdQueue: Queue[F, String],
    val errQueue: Queue[F, String]
) extends Console[F] {

  override def println(line: String): F[Unit]    =
    stdQueue.enqueue1(line)
  override def printlnErr(line: String): F[Unit] =
    errQueue.enqueue1(line)
}

object TestConsole {

  final def apply[F[_]: Concurrent]: F[TestConsole[F]] =
    for {
      std <- Queue.circularBuffer[F, String](100)
      err <- Queue.circularBuffer[F, String](100)
    } yield new TestConsole[F](std, err)

}
