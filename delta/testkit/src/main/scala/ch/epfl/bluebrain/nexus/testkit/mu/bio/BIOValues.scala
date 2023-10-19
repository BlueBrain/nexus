package ch.epfl.bluebrain.nexus.testkit.mu.bio

import monix.bio.{IO, UIO}
import monix.execution.Scheduler
import munit.Assertions.fail
import munit.{Location, Suite}

import java.io.{ByteArrayOutputStream, PrintStream}
import scala.concurrent.duration.Duration
import scala.reflect.ClassTag
import scala.util.control.NonFatal

trait BIOValues {
  self: Suite =>

  implicit class UIOValuesOps[A](private val uio: UIO[A]) {
    def accepted(implicit s: Scheduler = Scheduler.global): A =
      uio.runSyncUnsafe()
  }

  implicit class IOValuesOps[E, A](private val io: IO[E, A])(implicit E: ClassTag[E]) {

    def accepted(implicit loc: Location, s: Scheduler = Scheduler.global): A = acceptedWithTimeout(Duration.Inf)

    def acceptedWithTimeout(timeout: Duration)(implicit loc: Location, s: Scheduler = Scheduler.global): A =
      io.attempt.runSyncUnsafe(timeout) match {
        case Left(NonFatal(err)) =>
          val baos  = new ByteArrayOutputStream()
          err.printStackTrace(new PrintStream(baos))
          val stack = new String(baos.toByteArray)
          fail(
            s"""Error caught of type '${err.getClass.getName}', expected a successful response
               |Message: ${err.getMessage}
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

    def rejected(implicit loc: Location, s: Scheduler = Scheduler.global): E =
      rejectedWith[E]

    def rejectedWith[EE <: E](implicit loc: Location, EE: ClassTag[EE], s: Scheduler = Scheduler.global): EE = {
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
}
