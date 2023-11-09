package ch.epfl.bluebrain.nexus.delta.kernel.effect.migration

import cats.effect.IO
import monix.bio.{IO => BIO, Task, UIO}
import monix.execution.Scheduler.Implicits.global
import shapeless.=:!=

import scala.annotation.nowarn

import scala.reflect.ClassTag

trait MigrateEffectSyntax {

  implicit def toCatsIO[E <: Throwable, A](io: BIO[E, A]): IO[A]                        = io.to[IO]
  implicit def uioToCatsIO[E <: Throwable, A](io: UIO[A]): IO[A]                        = io.to[IO]
  implicit def toCatsIOOps[E <: Throwable, A](io: BIO[E, A]): MonixBioToCatsIOOps[E, A] = new MonixBioToCatsIOOps(io)

  implicit def toMonixBIOOps[A](io: IO[A]): CatsIOToBioOps[A] = new CatsIOToBioOps(io)

}

final class MonixBioToCatsIOOps[E <: Throwable, A](private val io: BIO[E, A]) extends AnyVal {
  def toCatsIO: IO[A] = io.to[IO]
}

final class CatsIOToBioOps[A](private val io: IO[A]) extends AnyVal {

  /**
    * Safe conversion between CE and Monix, forcing the user to specify a strict subtype of [[Throwable]]. If omitted,
    * the compiler may infer [[Throwable]] and bypass any custom error handling.
    */
  @SuppressWarnings(Array("UnusedMethodParameter"))
  @nowarn
  def toBIO[E <: Throwable](implicit E: ClassTag[E], ev: E =:!= Throwable): BIO[E, A] =
    toBIOThrowable[E]

  /**
    * Prefer [[toBIO]]. Only use this when we are sure there's no custom error handling logic.
    */
  def toBIOThrowable[E <: Throwable](implicit E: ClassTag[E]): BIO[E, A] =
    BIO.from(io).mapErrorPartialWith {
      case E(e)  => monix.bio.IO.raiseError(e)
      case other => BIO.terminate(other)
    }

  def toUIO: UIO[A] = BIO.from(io).hideErrors

  def toTask: Task[A] = Task.from(io)
}
