package ch.epfl.bluebrain.nexus.delta.sourcing

import monix.bio.Cause.{Error, Termination}
import monix.bio.{IO, UIO}
import munit.{Assertions, Location}

import java.io.{ByteArrayOutputStream, PrintStream}
import scala.concurrent.duration.FiniteDuration
import scala.reflect.ClassTag
import scala.util.control.NonFatal

trait MonixBioAssertions { self: Assertions =>

  def assertIO[E, A](io: IO[E, A], expected: A)(implicit E: ClassTag[E], loc: Location): UIO[Unit] =
    io.attempt.map {
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

  def assertIOSome[E, A](io: IO[E, A], expected: A)(implicit E: ClassTag[E], loc: Location): UIO[Unit] =
    assertIO(io, Some(expected))

  def assertIONone[E, A](io: IO[E, A])(implicit E: ClassTag[E], loc: Location): UIO[Unit] =
    assertIO(io, None)

  def assertIO[E, A](io: IO[E, A], expected: A, timeout: FiniteDuration)(implicit
      E: ClassTag[E],
      loc: Location
  ): UIO[Unit] =
    assertIO(io.timeout(timeout), expected)

  def assertError[E, A](io: IO[E, A], expected: E)(implicit E: ClassTag[E], loc: Location): UIO[Unit] =
    io.attempt.map {
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

  def assertTerminal[T <: Throwable](io: IO[_, _], expectedMessage: String)(implicit
      T: ClassTag[T],
      loc: Location
  ): UIO[Unit] =
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

  def assertTerminal[T <: Throwable](io: IO[_, _], expected: T)(implicit
      T: ClassTag[T],
      loc: Location
  ): UIO[Unit] =
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

  implicit class MonixBioAsertionsOps[E, A](io: IO[E, A])(implicit E: ClassTag[E]) {

    def assert(expected: A)(implicit loc: Location): UIO[Unit] = assertIO(io, expected)

    def assert(expected: A, timeout: FiniteDuration)(implicit loc: Location): UIO[Unit] =
      assertIO(io, expected, timeout)

    def error(expected: E)(implicit loc: Location): UIO[Unit] = assertError(io, expected)

    def terminated[T <: Throwable](expectedMessage: String)(implicit T: ClassTag[T], loc: Location): UIO[Unit] =
      assertTerminal[T](io, expectedMessage)

    def terminated[T <: Throwable](expected: T)(implicit T: ClassTag[T], loc: Location): UIO[Unit] =
      assertTerminal[T](io, expected)
  }

  implicit class MonixBioAsertionsOptionOps[E, A](io: IO[E, Option[A]])(implicit E: ClassTag[E]) {
    def assertSome(expected: A)(implicit loc: Location): UIO[Unit] = assertIO(io, Some(expected))

    def assertNone(implicit loc: Location): UIO[Unit] = assertIONone(io)
  }

}

object MonixBioAssertions extends Assertions with MonixBioAssertions {}
