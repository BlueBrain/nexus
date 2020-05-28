package ch.epfl.bluebrain.nexus.sourcing.projections

import cats.effect.IO
import org.scalactic.source
import org.scalatest.matchers.should.Matchers._
import org.scalatest.concurrent.ScalaFutures

import scala.reflect.ClassTag

trait IOValues extends ScalaFutures {
  implicit final def ioValues[A](io: IO[A]): IOValuesSyntax[A] =
    new IOValuesSyntax(io)

  protected class IOValuesSyntax[A](io: IO[A]) {
    def failed[Ex <: Throwable: ClassTag](implicit config: PatienceConfig, pos: source.Position): Ex = {
      val Ex = implicitly[ClassTag[Ex]]
      io.redeemWith(
          {
            case Ex(ex) => IO.pure(ex)
            case other =>
              IO(
                fail(
                  s"Wrong throwable type caught, expected: '${Ex.runtimeClass.getName}', actual: '${other.getClass.getName}'"
                )
              )
          },
          a => IO(fail(s"The IO did not fail as expected, but computed the value '$a'"))
        )
        .ioValue(config, pos)
    }

    def ioValue(implicit config: PatienceConfig, pos: source.Position): A =
      io.unsafeToFuture().futureValue(config, pos)
  }
}

object IOValues extends IOValues
