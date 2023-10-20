package ch.epfl.bluebrain.nexus.delta.kernel.effect.migration

import cats.effect.IO
import cats.~>
import monix.bio.{IO => BIO, Task, UIO}
import monix.execution.Scheduler.Implicits.global
import shapeless.=:!=

import scala.annotation.nowarn
import scala.reflect.ClassTag

trait MigrateEffectSyntax {

  implicit def toCatsIO[E <: Throwable, A](io: BIO[E, A]): IO[A]                        = io.to[IO]
  implicit def uioToCatsIO[E <: Throwable, A](io: UIO[A]): IO[A]                        = io.to[IO]
  implicit def toCatsIOOps[E <: Throwable, A](io: BIO[E, A]): MonixBioToCatsIOOps[E, A] = new MonixBioToCatsIOOps(io)
  implicit def toCatsIOEitherOps[E, A](io: BIO[E, A]): MonixBioToCatsIOEitherOps[E, A]  = new MonixBioToCatsIOEitherOps(
    io
  )

  implicit def toMonixBIOOps[A](io: IO[A]): CatsIOToBioOps[A] = new CatsIOToBioOps(io)

  val taskToIoK: Task ~> IO = λ[Task ~> IO](toCatsIO(_))
  val uioToIoK: UIO ~> IO   = λ[UIO ~> IO](uioToCatsIO(_))
  val ioToUioK: IO ~> UIO   = λ[IO ~> UIO](_.toUIO)
  val ioToTaskK: IO ~> Task = λ[IO ~> Task](Task.from(_))

}

final class MonixBioToCatsIOOps[E <: Throwable, A](private val io: BIO[E, A]) extends AnyVal {
  def toCatsIO: IO[A] = io.to[IO]
}

final class MonixBioToCatsIOEitherOps[E, A](private val io: BIO[E, A]) extends AnyVal {
  def toCatsIOEither: IO[Either[E, A]] = io.attempt.to[IO]
}

final class CatsIOToBioOps[A](private val io: IO[A]) extends AnyVal {

  /**
    * Safe conversion between CE and Monix, forcing the user to specify a strict subtype of [[Throwable]]. If omitted,
    * the compiler may infer [[Throwable]] and bypass any custom error handling.
    */
  @nowarn
  def toBIO[E <: Throwable](implicit E: ClassTag[E], ev: E =:!= Throwable): BIO[E, A] =
    toBIOUnsafe[E]

  /**
    * Prefer [[toBIO]]. Only use this when we are sure there's no custom error handling logic.
    */
  def toBIOUnsafe[E <: Throwable](implicit E: ClassTag[E]): BIO[E, A] =
    BIO.from(io).mapErrorPartialWith {
      case E(e)  => monix.bio.IO.raiseError(e)
      case other => BIO.terminate(other)
    }

  def toUIO: UIO[A] = BIO.from(io).hideErrors

  def toTask: Task[A] = Task.from(io)
}
