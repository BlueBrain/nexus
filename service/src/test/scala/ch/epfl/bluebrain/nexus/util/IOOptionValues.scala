package ch.epfl.bluebrain.nexus.util

import cats.effect.IO
import org.scalactic.source
import org.scalatest.OptionValues

trait IOOptionValues extends IOValues with OptionValues {

  implicit final def ioOptionValues[A](io: IO[Option[A]]): IOOptionValuesSyntax[A] =
    new IOOptionValuesSyntax(io)

  protected class IOOptionValuesSyntax[A](io: IO[Option[A]]) {
    def some(implicit config: PatienceConfig, pos: source.Position): A =
      io.ioValue(config, pos).value
  }
}

object IOOptionValues extends IOOptionValues
