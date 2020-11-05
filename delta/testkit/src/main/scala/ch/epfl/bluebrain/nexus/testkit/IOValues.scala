package ch.epfl.bluebrain.nexus.testkit

import java.io.{ByteArrayOutputStream, PrintStream}

import monix.bio.{IO, Task, UIO}
import monix.execution.Scheduler
import org.scalactic.source
import org.scalatest.matchers.should.Matchers.fail
import org.scalatest.{Assertion, Assertions}

import scala.concurrent.Future
import scala.reflect.ClassTag
import scala.util.control.NonFatal

trait IOValues extends IOValuesLowPrio {
  implicit def taskToFutureAssertion(
      task: Task[Assertion]
  )(implicit s: Scheduler = Scheduler.global): Future[Assertion] =
    task.runToFuture

  implicit def futureListToFutureAssertion(
      future: Future[List[Assertion]]
  )(implicit s: Scheduler = Scheduler.global): Future[Assertion] =
    future.map(_ => succeed)

  implicit final def uioValuesSyntax[A](uio: UIO[A]): UIOValuesOps[A] = new UIOValuesOps(uio)

  implicit final def ioValuesSyntax[E: ClassTag, A](io: IO[E, A]): IOValuesOps[E, A] = new IOValuesOps(io)
}

trait IOValuesLowPrio extends Assertions {
  implicit def taskListToFutureAssertion(
      task: Task[List[Assertion]]
  )(implicit s: Scheduler = Scheduler.global): Future[Assertion] =
    task.runToFuture.map(_ => succeed)
}

final class UIOValuesOps[A](private val uio: UIO[A]) {

  def accepted(implicit s: Scheduler = Scheduler.global): A =
    uio.runSyncUnsafe()
}

final class IOValuesOps[E, A](private val io: IO[E, A])(implicit E: ClassTag[E]) {

  def accepted(implicit pos: source.Position, s: Scheduler = Scheduler.global): A =
    io.attempt.runSyncUnsafe() match {
      case Left(NonFatal(err)) =>
        val baos  = new ByteArrayOutputStream()
        err.printStackTrace(new PrintStream(baos))
        val stack = new String(baos.toByteArray)
        fail(
          s"""Error caught of type '${err.getClass.getName}', expected a successful response
             |Message: ${err.toString}
             |Stack:
             |$stack""".stripMargin,
          err
        )
      case Left(err)           =>
        fail(
          s"""Error caught of type '${E.runtimeClass.getName}', expected a successful response
             |Message: ${err.toString}""".stripMargin
        )
      case Right(value)        =>
        value
    }

  def rejected(implicit pos: source.Position, s: Scheduler = Scheduler.global): E =
    rejectedWith[E]

  def rejectedWith[EE <: E](implicit pos: source.Position, EE: ClassTag[EE], s: Scheduler = Scheduler.global): EE = {
    io.attempt.runSyncUnsafe() match {
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
