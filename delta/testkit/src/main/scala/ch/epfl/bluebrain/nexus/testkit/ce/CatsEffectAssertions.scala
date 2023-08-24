package ch.epfl.bluebrain.nexus.testkit.ce

import cats.effect.IO
import cats.syntax.eq._

import scala.reflect.ClassTag
import scala.util.control.NonFatal
import cats.effect.SyncIO
import cats.effect.Sync
import munit.{Assertions, FailException, Location}

trait CatsEffectAssertions { self: Assertions =>

  /**
    * Asserts that an `IO` returns an expected value.
    *
    * The "returns" value (second argument) must have the same type or be a subtype of the one "contained" inside the
    * `IO` (first argument). For example:
    * {{{
    *   assertIO(IO(Option(1)), returns = Some(1)) // OK
    *   assertIO(IO(Some(1)), returns = Option(1)) // Error: Option[Int] is not a subtype of Some[Int]
    * }}}
    *
    * The "clue" value can be used to give extra information about the failure in case the assertion fails.
    *
    * @param obtained
    *   the IO under testing
    * @param returns
    *   the expected value
    * @param clue
    *   a value that will be printed in case the assertions fails
    */
  def assertIO[A, B](
      obtained: IO[A],
      returns: B,
      clue: => Any = "values are not the same"
  )(implicit loc: Location, ev: B <:< A): IO[Unit] =
    obtained.flatMap(a => IO(assertEquals(a, returns, clue)))

  /**
    * Asserts that an `IO[Unit]` returns the Unit value.
    *
    * For example:
    * {{{
    *   assertIO_(IO.unit)
    * }}}
    *
    * The "clue" value can be used to give extra information about the failure in case the assertion fails.
    *
    * @param obtained
    *   the IO under testing
    * @param clue
    *   a value that will be printed in case the assertions fails
    */
  protected def assertIO_(
      obtained: IO[Unit],
      clue: => Any = "value is not ()"
  )(implicit loc: Location): IO[Unit] =
    obtained.flatMap(a => IO(assertEquals(a, (), clue)))

  /**
    * Asserts that an `IO[Boolean]` returns true.
    *
    * For example:
    * {{{
    *   assertIOBoolean(IO(true))
    * }}}
    *
    * The "clue" value can be used to give extra information about the failure in case the assertion fails.
    *
    * @param obtained
    *   the IO[Boolean] under testing
    * @param clue
    *   a value that will be printed in case the assertions fails
    */
  protected def assertIOBoolean(
      obtained: IO[Boolean],
      clue: => Any = "values are not the same"
  )(implicit loc: Location): IO[Unit] =
    assertIO(obtained, true, clue)

  /**
    * Intercepts a `Throwable` being thrown inside the provided `IO`.
    *
    * @example
    * {{{
    *   val io = IO.raiseError[Unit](MyException("BOOM!"))
    *
    *   interceptIO[MyException](io)
    * }}}
    *
    * or
    *
    * {{{
    *   interceptIO[MyException] {
    *       IO.raiseError[Unit](MyException("BOOM!"))
    *   }
    * }}}
    */
  def interceptIO[T <: Throwable](io: IO[Any])(implicit T: ClassTag[T], loc: Location): IO[T] =
    io.attempt.flatMap[T](runInterceptMessage[IO, T](None))

  /**
    * Intercepts a `Throwable` with a certain message being thrown inside the provided `IO`.
    *
    * @example
    * {{{
    *   val io = IO.raiseError[Unit](MyException("BOOM!"))
    *
    *   interceptIO[MyException]("BOOM!")(io)
    * }}}
    *
    * or
    *
    * {{{
    *   interceptIO[MyException] {
    *       IO.raiseError[Unit](MyException("BOOM!"))
    *   }
    * }}}
    */
  def interceptMessageIO[T <: Throwable](
      expectedExceptionMessage: String
  )(io: IO[Any])(implicit T: ClassTag[T], loc: Location): IO[T] =
    io.attempt.flatMap[T](runInterceptMessage[IO, T](Some(expectedExceptionMessage)))

  /**
    * Intercepts a `Throwable` with a certain value being thrown inside the provided `IO`.
    */
  def interceptErrorIO[T <: Throwable](expectedError: T)(io: IO[Any])(implicit T: ClassTag[T], loc: Location): IO[T] =
    io.attempt.flatMap[T](runInterceptValue[IO, T](expectedError))

  /**
    * Asserts that a `SyncIO` returns an expected value.
    *
    * The "returns" value (second argument) must have the same type or be a subtype of the one "contained" inside the
    * `SyncIO` (first argument). For example:
    * {{{
    *   assertSyncIO(SyncIO(Option(1)), returns = Some(1)) // OK
    *   assertSyncIO(SyncIO(Some(1)), returns = Option(1)) // Error: Option[Int] is not a subtype of Some[Int]
    * }}}
    *
    * The "clue" value can be used to give extra information about the failure in case the assertion fails.
    *
    * @param obtained
    *   the SyncIO under testing
    * @param returns
    *   the expected value
    * @param clue
    *   a value that will be printed in case the assertions fails
    */
  def assertSyncIO[A, B](
      obtained: SyncIO[A],
      returns: B,
      clue: => Any = "values are not the same"
  )(implicit loc: Location, ev: B <:< A): SyncIO[Unit] =
    obtained.flatMap(a => SyncIO(assertEquals(a, returns, clue)))

  /**
    * Asserts that a `SyncIO[Unit]` returns the Unit value.
    *
    * For example:
    * {{{
    *   assertSyncIO_(SyncIO.unit) // OK
    * }}}
    *
    * The "clue" value can be used to give extra information about the failure in case the assertion fails.
    *
    * @param obtained
    *   the SyncIO under testing
    * @param clue
    *   a value that will be printed in case the assertions fails
    */
  protected def assertSyncIO_(
      obtained: SyncIO[Unit],
      clue: => Any = "value is not ()"
  )(implicit loc: Location): SyncIO[Unit] =
    obtained.flatMap(a => SyncIO(assertEquals(a, (), clue)))

  /**
    * Intercepts a `Throwable` being thrown inside the provided `SyncIO`.
    *
    * @example
    * {{{
    *   val io = SyncIO.raiseError[Unit](MyException("BOOM!"))
    *
    *   interceptSyncIO[MyException](io)
    * }}}
    *
    * or
    *
    * {{{
    *   interceptSyncIO[MyException] {
    *       SyncIO.raiseError[Unit](MyException("BOOM!"))
    *   }
    * }}}
    */
  def interceptSyncIO[T <: Throwable](
      io: SyncIO[Any]
  )(implicit T: ClassTag[T], loc: Location): SyncIO[T] =
    io.attempt.flatMap[T](runInterceptMessage[SyncIO, T](None))

  /**
    * Intercepts a `Throwable` with a certain message being thrown inside the provided `SyncIO`.
    *
    * @example
    * {{{
    *   val io = SyncIO.raiseError[Unit](MyException("BOOM!"))
    *
    *   interceptSyncIO[MyException]("BOOM!")(io)
    * }}}
    *
    * or
    *
    * {{{
    *   interceptSyncIO[MyException] {
    *       SyncIO.raiseError[Unit](MyException("BOOM!"))
    *   }
    * }}}
    */
  def interceptMessageSyncIO[T <: Throwable](
      expectedExceptionMessage: String
  )(io: SyncIO[Any])(implicit T: ClassTag[T], loc: Location): SyncIO[T] =
    io.attempt.flatMap[T](runInterceptMessage[SyncIO, T](Some(expectedExceptionMessage)))

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

  implicit class MUnitCatsAssertionsForSyncIOOps[A](io: SyncIO[A]) {

    /**
      * Asserts that this effect returns an expected value.
      *
      * The "expected" value (second argument) must have the same type or be a subtype of the one "contained" inside the
      * effect. For example:
      * {{{
      *   SyncIO(Option(1)).assertEquals(Some(1)) // OK
      *   SyncIO(Some(1)).assertEquals(Option(1)) // Error: Option[Int] is not a subtype of Some[Int]
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
    )(implicit loc: Location, ev: B <:< A): SyncIO[Unit] =
      assertSyncIO(io, expected, clue)

    /**
      * Intercepts a `Throwable` being thrown inside this effect.
      *
      * @example
      * {{{
      *   val io = SyncIO.raiseError[Unit](MyException("BOOM!"))
      *
      *   io.intercept[MyException]
      * }}}
      */
    def intercept[T <: Throwable](implicit T: ClassTag[T], loc: Location): SyncIO[T] =
      interceptSyncIO[T](io)

    /**
      * Intercepts a `Throwable` with a certain message being thrown inside this effect.
      *
      * @example
      * {{{
      *   val io = SyncIO.raiseError[Unit](MyException("BOOM!"))
      *
      *   io.intercept[MyException]("BOOM!")
      * }}}
      */
    def interceptMessage[T <: Throwable](
        expectedExceptionMessage: String
    )(implicit T: ClassTag[T], loc: Location): SyncIO[T] =
      interceptMessageSyncIO[T](expectedExceptionMessage)(io)

  }

  implicit class MUnitCatsAssertionsForSyncIOUnitOps(io: SyncIO[Unit]) {

    /**
      * Asserts that this effect returns the Unit value.
      *
      * For example:
      * {{{
      *   SyncIO.unit.assert // OK
      * }}}
      */
    def assert(implicit loc: Location): SyncIO[Unit] =
      assertSyncIO_(io)
  }
}

object CatsEffectAssertions extends Assertions with CatsEffectAssertions
