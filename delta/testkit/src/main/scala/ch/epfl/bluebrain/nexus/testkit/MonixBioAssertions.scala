package ch.epfl.bluebrain.nexus.testkit

import monix.bio.Cause.{Error, Termination}
import monix.bio.{IO, UIO}
import munit.{Assertions, Location}

import java.io.{ByteArrayOutputStream, PrintStream}
import scala.concurrent.duration.FiniteDuration
import scala.reflect.ClassTag
import scala.util.control.NonFatal

trait MonixBioAssertions { self: Assertions =>

  implicit class MonixBioAssertionsOps[E, A](io: IO[E, A])(implicit E: ClassTag[E], loc: Location) {

    def assert(expected: A): UIO[Unit] = io.attempt.map {
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
      case Right(a)            => assertEquals(a, expected)
    }

    def assert(expected: A, timeout: FiniteDuration): UIO[Unit] =
      io.timeout(timeout).assertSome(expected)

    def error(expected: E): UIO[Unit] = io.attempt.map {
      case Left(E(err)) => assertEquals(err, expected)
      case Left(err)    =>
        fail(
          s"Wrong raised error type caught, expected: '${E.runtimeClass.getName}', actual: '${err.getClass.getName}'"
        )
      case Right(a)     =>
        fail(
          s"Expected raising error, but returned successful response with type '${a.getClass.getName}'"
        )
    }

    def terminated[T <: Throwable](expectedMessage: String)(implicit T: ClassTag[T]): UIO[Unit] =
      io.redeemCause(
        {
          case Error(err)        =>
            fail(
              s"Wrong raised error type caught, expected terminal: '${T.runtimeClass.getName}', actual typed: '${err.getClass.getName}'"
            )
          case Termination(T(t)) => assertEquals(t.getMessage, expectedMessage)
          case Termination(t)    =>
            fail(
              s"Wrong raised error type caught, expected terminal: '${T.runtimeClass.getName}', actual terminal: '${t.getClass.getName}'"
            )
        },
        a =>
          fail(
            s"Expected raising error, but returned successful response with type '${a.getClass.getName}'"
          )
      )

    def terminated[T <: Throwable](expected: T)(implicit T: ClassTag[T]): UIO[Unit] =
      io.redeemCause(
        {
          case Error(err)        =>
            fail(
              s"Wrong raised error type caught, expected terminal: '${T.runtimeClass.getName}', actual typed: '${err.getClass.getName}'"
            )
          case Termination(T(t)) => assertEquals(t, expected)
          case Termination(t)    =>
            fail(
              s"Wrong raised error type caught, expected terminal: '${T.runtimeClass.getName}', actual terminal: '${t.getClass.getName}'"
            )
        },
        a =>
          fail(
            s"Expected raising error, but returned successful response with type '${a.getClass.getName}'"
          )
      )
  }

  implicit class MonixBioAssertionsOptionOps[E, A](io: IO[E, Option[A]])(implicit E: ClassTag[E], loc: Location) {
    def assertSome(expected: A): UIO[Unit] = io.assert(Some(expected))

    def assertNone: UIO[Unit] = io.assert(None)
  }

}

object MonixBioAssertions extends Assertions with MonixBioAssertions
