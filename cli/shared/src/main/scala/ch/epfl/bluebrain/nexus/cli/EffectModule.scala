package ch.epfl.bluebrain.nexus.cli

import cats.effect.{Async, Bracket, ConcurrentEffect, ContextShift, Effect, Sync, Timer}
import cats.{Applicative, ApplicativeError, Functor, Monad, MonadError, Parallel}
import distage.{ModuleDef, TagK}
import izumi.distage.model.effect.{DIApplicative, DIEffect, DIEffectAsync, DIEffectRunner}

/**
  * Module definition that binds effect TC instances for an arbitrary F[_].
  */
class EffectModule[F[_]: Parallel: ConcurrentEffect: ContextShift: Timer: TagK] extends ModuleDef {
  implicit private def diEffectRunner: DIEffectRunner[F] = new DIEffectRunner[F] {
    override def run[A](f: => F[A]): A = ConcurrentEffect[F].toIO(f).unsafeRunSync()
  }

  addImplicit[DIEffectRunner[F]]
  addImplicit[DIApplicative[F]]
  addImplicit[DIEffect[F]]
  addImplicit[DIEffectAsync[F]]

  addImplicit[Functor[F]]
  addImplicit[Applicative[F]]
  addImplicit[ApplicativeError[F, Throwable]]
  addImplicit[Monad[F]]
  addImplicit[MonadError[F, Throwable]]
  addImplicit[Bracket[F, Throwable]]
  addImplicit[Sync[F]]
  addImplicit[Async[F]]
  addImplicit[Effect[F]]
  addImplicit[ConcurrentEffect[F]]
  addImplicit[Timer[F]]
  addImplicit[ContextShift[F]]
}

object EffectModule {

  /**
    * Creates an EffectModule using TC instances from the implicit scope.
    */
  def apply[F[_]: Parallel: ConcurrentEffect: ContextShift: Timer: TagK]: EffectModule[F] = new EffectModule[F]
}
