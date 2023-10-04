package ch.epfl.bluebrain.nexus.testkit.ce

import cats.effect.IO
import org.scalactic.source
import org.scalatest.Assertions.fail

import scala.reflect.ClassTag

trait CatsIOValues {

  implicit final class CatsIOValuesOps[A](private val io: IO[A]) {
    def accepted: A = io.unsafeRunSync()

    def rejectedWith[E](implicit pos: source.Position, EE: ClassTag[E]): E = {
      io.attempt.unsafeRunSync() match {
        case Left(EE(value)) => value
        case Left(value)     =>
          fail(
            s"Wrong raised error type caught, expected: '${EE.runtimeClass.getName}', actual: '${value.getClass.getName}'"
          )
        case Right(value)    =>
          fail(
            s"Expected raising error, but returned successful response with type '${value.getClass.getName}'"
          )
      }
    }
  }

}
