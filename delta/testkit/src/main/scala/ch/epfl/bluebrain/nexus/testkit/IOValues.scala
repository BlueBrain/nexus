package ch.epfl.bluebrain.nexus.testkit

import monix.bio.IO
import monix.execution.Scheduler
import org.scalactic.source
import org.scalatest.matchers.should.Matchers.fail

import scala.reflect.ClassTag

trait IOValues {
  implicit final def ioValuesSyntax[E: ClassTag, A](io: IO[E, A]): IOValuesOps[E, A] = new IOValuesOps(io)
}

final class IOValuesOps[E, A](private val io: IO[E, A])(implicit E: ClassTag[E]) {

  def accepted(implicit pos: source.Position, s: Scheduler): A =
    io.attempt.runSyncUnsafe() match {
      case Left(err)      =>
        fail(s"Error caught of type '${E.runtimeClass.getName}', expected a successful response\nMessage: ${err.toString}")
      case Right(value) =>
        value
    }

  def rejected(implicit pos: source.Position, s: Scheduler): E =
    rejectedWith[E]

  def rejectedWith[EE <: E](implicit pos: source.Position, EE: ClassTag[EE], s: Scheduler): EE = {
    io.attempt.runSyncUnsafe() match {
      case Left(EE(value)) => value
      case Left(value)     =>
        fail(
          s"Wrong raised error type caught, expected: '${EE.runtimeClass.getName}', actual: '${value.getClass.getName}'"
        )
      case Right(value)    =>
        fail(
          s"Expected raising error, but returned successfull response with type '${value.getClass.getName}'"
        )
    }
  }
}
