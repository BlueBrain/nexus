package ch.epfl.bluebrain.nexus.testkit.ce

import cats.effect.{IO, Sync}
import cats.syntax.eq._
import munit.{Assertions, FailException, Location}

import scala.reflect.ClassTag
import scala.util.control.NonFatal

/**
  * Adapted from: from
  * https://github.com/typelevel/munit-cats-effect/blob/main/core/src/main/scala/munit/CatsEffectAssertions.scala
  */
trait CatsEffectAssertions { self: Assertions =>

  def assertIO[A, B](
      obtained: IO[A],
      returns: B,
      clue: => Any = "values are not the same"
  )(implicit loc: Location, ev: B <:< A): IO[Unit] =
    obtained.flatMap(a => IO(assertEquals(a, returns, clue)))

  protected def assertIO_(
      obtained: IO[Unit],
      clue: => Any = "value is not ()"
  )(implicit loc: Location): IO[Unit] =
    obtained.flatMap(a => IO(assertEquals(a, (), clue)))

  protected def assertIOBoolean(
      obtained: IO[Boolean],
      clue: => Any = "values are not the same"
  )(implicit loc: Location): IO[Unit] =
    assertIO(obtained, true, clue)

  def interceptIO[T <: Throwable](io: IO[Any])(implicit T: ClassTag[T], loc: Location): IO[T] =
    io.attempt.flatMap[T](runInterceptMessage[IO, T](None))

  def interceptMessageIO[T <: Throwable](
      expectedExceptionMessage: String
  )(io: IO[Any])(implicit T: ClassTag[T], loc: Location): IO[T] =
    io.attempt.flatMap[T](runInterceptMessage[IO, T](Some(expectedExceptionMessage)))

  def interceptErrorIO[T <: Throwable](expectedError: T)(io: IO[Any])(implicit T: ClassTag[T], loc: Location): IO[T] =
    io.attempt.flatMap[T](runInterceptValue[IO, T](expectedError))

  /**
    * Copied from `munit.Assertions` and adapted to return `IO[T]` instead of `T`.
    */
  private def runInterceptMessage[F[_]: Sync, T <: Throwable](
      expectedExceptionMessage: Option[String]
  )(implicit T: ClassTag[T], loc: Location): Either[Throwable, Any] => F[T] = {
    case Right(value)                                                                =>
      Sync[F].delay {
        fail(
          s"expected exception of type '${T.runtimeClass.getName}' but body evaluated successfully",
          clues(value)
        )
      }
    case Left(e: FailException) if !T.runtimeClass.isAssignableFrom(e.getClass)      =>
      Sync[F].raiseError[T](e)
    case Left(NonFatal(e: T)) if expectedExceptionMessage.forall(_ === e.getMessage) =>
      Sync[F].pure(e)
    case Left(NonFatal(e: T))                                                        =>
      Sync[F].raiseError[T] {
        val obtained = e.getClass.getName

        new FailException(
          s"intercept failed, exception '$obtained' had message '${e.getMessage}', " +
            s"which was different from expected message '${expectedExceptionMessage.get}'",
          cause = e,
          isStackTracesEnabled = false,
          location = loc
        )
      }
    case Left(NonFatal(e))                                                           =>
      Sync[F].raiseError[T] {
        val obtained = e.getClass.getName
        val expected = T.runtimeClass.getName

        new FailException(
          s"intercept failed, exception '$obtained' is not a subtype of '$expected",
          cause = e,
          isStackTracesEnabled = false,
          location = loc
        )
      }
    case Left(e)                                                                     =>
      Sync[F].raiseError[T](e)
  }

  private def runInterceptValue[F[_]: Sync, T <: Throwable](
      expectedError: T
  )(implicit T: ClassTag[T], loc: Location): Either[Throwable, Any] => F[T] = {
    case Right(value)                                                           =>
      Sync[F].delay {
        fail(
          s"expected exception of type '${T.runtimeClass.getName}' but body evaluated successfully",
          clues(value)
        )
      }
    case Left(e: FailException) if !T.runtimeClass.isAssignableFrom(e.getClass) =>
      Sync[F].raiseError[T](e)
    case Left(NonFatal(e: T)) if e == expectedError                             =>
      Sync[F].pure(e)
    case Left(NonFatal(e: T))                                                   =>
      Sync[F].raiseError[T] {
        val obtained = e.getClass.getName

        new FailException(
          s"intercept failed, exception '$obtained' had value '$e', " +
            s"which was different from expected value '$expectedError'",
          cause = e,
          isStackTracesEnabled = false,
          location = loc
        )
      }
    case Left(NonFatal(e))                                                      =>
      Sync[F].raiseError[T] {
        val obtained = e.getClass.getName
        val expected = T.runtimeClass.getName

        new FailException(
          s"intercept failed, exception '$obtained' is not a subtype of '$expected",
          cause = e,
          isStackTracesEnabled = false,
          location = loc
        )
      }
    case Left(e)                                                                =>
      Sync[F].raiseError[T](e)
  }

  implicit class MUnitCatsAssertionsForIOOps[A](io: IO[A]) {

    /**
      * Asserts that this effect returns an expected value.
      *
      * The "expected" value (second argument) must have the same type or be a subtype of the one "contained" inside the
      * effect. For example:
      * {{{
      *   IO(Option(1)).assertEquals(Some(1)) // OK
      *   IO(Some(1)).assertEquals(Option(1)) // Error: Option[Int] is not a subtype of Some[Int]
      * }}}
      *
      * The "clue" value can be used to give extra information about the failure in case the assertion fails.
      *
      * @param expected
      *   the expected value
      * @param clue
      *   a value that will be printed in case the assertions fails
      */
    def assertEquals[B](
        expected: B,
        clue: => Any = "values are not the same"
    )(implicit loc: Location, ev: B <:< A): IO[Unit] =
      assertIO(io, expected, clue)

    /**
      * Intercepts a `Throwable` being thrown inside this effect.
      *
      * @example
      * {{{
      *   val io = IO.raiseError[Unit](MyException("BOOM!"))
      *
      *   io.intercept[MyException]
      * }}}
      */
    def intercept[T <: Throwable](implicit T: ClassTag[T], loc: Location): IO[T] =
      interceptIO[T](io)

    /**
      * Intercepts a `Throwable` with a certain message being thrown inside this effect.
      *
      * @example
      * {{{
      *   val io = IO.raiseError[Unit](MyException("BOOM!"))
      *
      *   io.intercept[MyException]("BOOM!")
      * }}}
      */
    def interceptMessage[T <: Throwable](
        expectedExceptionMessage: String
    )(implicit T: ClassTag[T], loc: Location): IO[T] =
      interceptMessageIO[T](expectedExceptionMessage)(io)

    /**
      * Intercepts a `Throwable` with a certain message being thrown inside this effect.
      */
    def intercept[T <: Throwable](expectedError: T)(implicit T: ClassTag[T], loc: Location): IO[T] =
      interceptErrorIO[T](expectedError)(io)

  }

  implicit class MUnitCatsAssertionsForIOUnitOps(io: IO[Unit]) {

    /**
      * Asserts that this effect returns the Unit value.
      *
      * For example:
      * {{{
      *   IO.unit.assert // OK
      * }}}
      */
    def assert(implicit loc: Location): IO[Unit] =
      assertIO_(io)
  }

  implicit class MUnitCatsAssertionsForIOBooleanOps(io: IO[Boolean]) {

    /**
      * Asserts that this effect returns an expected value.
      *
      * For example:
      * {{{
      *   IO(true).assert // OK
      * }}}
      */
    def assert(implicit loc: Location): IO[Unit] =
      assertIOBoolean(io, "value is not true")
  }
}

object CatsEffectAssertions extends Assertions with CatsEffectAssertions
