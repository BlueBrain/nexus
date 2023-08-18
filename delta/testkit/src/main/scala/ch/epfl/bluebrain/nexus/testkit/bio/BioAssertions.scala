package ch.epfl.bluebrain.nexus.testkit.bio

import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategy
import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategyConfig.MaximumCumulativeDelayConfig
import monix.bio.Cause.{Error, Termination}
import monix.bio.{IO, UIO}
import munit.{Assertions, Location}
import ch.epfl.bluebrain.nexus.delta.kernel.syntax._

import java.io.{ByteArrayOutputStream, PrintStream}
import scala.concurrent.duration.FiniteDuration
import scala.reflect.ClassTag
import scala.util.control.NonFatal

trait BioAssertions { self: Assertions =>

  def assertUIO[A](io: UIO[A], cond: A => Boolean, clue: => Any = "assertion failed")(implicit
      loc: Location
  ): UIO[Unit] = {
    io.map(result => assert(cond(result), clue))
  }

  def assertEqualsIO[E, A, B](
      obtained: IO[E, A],
      returns: B,
      clue: => Any = "values are not the same"
  )(implicit loc: Location, ev: B <:< A): IO[E, Unit] =
    obtained.flatMap(a => UIO(assertEquals(a, returns, clue)))

  def assertError[E, A](obtained: IO[E, A], condition: E => Boolean, clue: E => Any = (_: E) => "assertion failed")(
      implicit loc: Location
  ): IO[E, Unit] = {
    obtained.attempt.map {
      case Left(err) => Assertions.assert(condition(err), clue(err))
      case Right(a)  =>
        fail(
          s"Expected a raised error, but returned successful response: $a"
        )
    }
  }

  implicit class UioAssertionsOps[A](uio: UIO[A])(implicit loc: Location) {
    def assert(expected: A, clue: Any = "values are not the same"): UIO[Unit] =
      uio.map(assertEquals(_, expected, clue))

    def assert(expected: A, timeout: FiniteDuration): UIO[Unit] =
      uio.timeout(timeout).assertSome(expected)
  }

  implicit class IoAssertionsOps[E, A](io: IO[E, A])(implicit E: ClassTag[E], loc: Location) {

    private def exceptionHandler(err: Throwable) = {
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
    }

    def assert(expected: A, clue: Any = "values are not the same")(implicit loc: Location): UIO[Unit] = io.redeemCause(
      {
        case Error(NonFatal(err)) =>
          exceptionHandler(err)
        case Error(err)           =>
          fail(
            s"""Error caught of type '${E.runtimeClass.getName}', expected a successful response
               |Message: ${err.toString}""".stripMargin
          )
        case Termination(err)     => exceptionHandler(err)
      },
      a => assertEquals(a, expected, clue)
    )

    def eventually(expected: A, retryWhen: Throwable => Boolean)(implicit patience: PatienceConfig): UIO[Unit] = {
      val strategy = RetryStrategy[Throwable](
        MaximumCumulativeDelayConfig(patience.timeout, patience.interval),
        retryWhen,
        onError = (_, _) => UIO.unit
      )
      assert(expected, patience.timeout).absorb
        .retry(strategy)
        .hideErrors
    }

    def eventually(expected: A)(implicit patience: PatienceConfig): UIO[Unit] =
      eventually(
        expected,
        {
          case _: AssertionError => true
          case _                 => false
        }
      )

    def assert(expected: A, timeout: FiniteDuration): UIO[Unit] =
      io.timeout(timeout).assertSome(expected)

    def assertError(condition: E => Boolean, clue: => Any = "assertion failed")(implicit loc: Location): UIO[Unit] = {
      io.attempt.map {
        case Left(E(err)) => Assertions.assert(condition(err), clue)
        case Left(err)    =>
          fail(
            s"Wrong raised error type caught, expected: '${E.runtimeClass.getName}', actual: '${err.getClass.getName}'"
          )
        case Right(a)     =>
          fail(
            s"Expected a raised error, but returned successful response: $a"
          )
      }
    }

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

    def terminated[T <: Throwable](implicit T: ClassTag[T]): UIO[Unit] =
      io.redeemCause(
        {
          case Error(err)        =>
            fail(
              s"Wrong raised error type caught, expected terminal: '${T.runtimeClass.getName}', actual typed: '${err.getClass.getName}'"
            )
          case Termination(T(_)) => ()
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

    def eventuallySome(expected: A)(implicit patience: PatienceConfig): UIO[Unit] = io.eventually(Some(expected))

    def eventuallyNone(implicit patience: PatienceConfig): UIO[Unit] = io.eventually(None)

    def assertNone: UIO[Unit] = io.assert(None)
  }

}
