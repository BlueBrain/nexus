package ch.epfl.bluebrain.nexus.storage.utils

import cats.effect.IO
import org.scalactic.source
import org.scalatest.matchers.should.Matchers.fail

import scala.reflect.ClassTag

trait IOEitherValues extends IOValues with EitherValues {

  implicit final def ioEitherValues[E, A](io: IO[Either[E, A]]): IOEitherValuesSyntax[E, A] =
    new IOEitherValuesSyntax(io)

  protected class IOEitherValuesSyntax[E, A](io: IO[Either[E, A]]) {
    def accepted(implicit config: PatienceConfig, pos: source.Position): A =
      io.ioValue(config, pos).rightValue

    def rejected[EE <: E: ClassTag](implicit config: PatienceConfig, pos: source.Position): EE = {
      val EE = implicitly[ClassTag[EE]]
      io.ioValue(config, pos).leftValue match {
        case EE(value) => value
        case other     =>
          fail(
            s"Wrong throwable type caught, expected: '${EE.runtimeClass.getName}', actual: '${other.getClass.getName}'"
          )
      }
    }
  }
}

object IOEitherValues extends IOEitherValues
