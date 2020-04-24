package ch.epfl.bluebrain.nexus.cli

import cats.effect.Sync

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
  final def apply[F[_]: Sync]: Console[F] =
    new LiveConsole[F]

  /**
    * An implementation of a console backed up by the Scala Console and delay if the ''F'' effect context
    */
  // $COVERAGE-OFF$
  final class LiveConsole[F[_]](implicit F: Sync[F]) extends Console[F] {
    override def println(line: String): F[Unit]    = F.delay(ScalaConsole.out.println(line))
    override def printlnErr(line: String): F[Unit] = F.delay(ScalaConsole.err.println(line))
  }
  // $COVERAGE-ON$
}
