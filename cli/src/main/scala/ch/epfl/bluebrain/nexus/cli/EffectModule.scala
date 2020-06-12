package ch.epfl.bluebrain.nexus.cli

import java.util.concurrent.{Executors, ThreadFactory}

import cats.effect.{Async, Blocker, Bracket, Concurrent, ConcurrentEffect, ContextShift, Effect, Sync, Timer}
import cats.{Applicative, ApplicativeError, Functor, Monad, MonadError, Parallel}
import distage.{ModuleDef, TagK}
import izumi.distage.model.effect.{DIApplicative, DIEffect, DIEffectAsync, DIEffectRunner}

import scala.concurrent.ExecutionContext

/**
  * Module definition that binds effect TC instances for an arbitrary F[_].
  */
final class EffectModule[F[_]: Parallel: ConcurrentEffect: ContextShift: Timer: TagK] extends ModuleDef {
  implicit private def diEffectRunner: DIEffectRunner[F] =
    new DIEffectRunner[F] {
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
  addImplicit[Concurrent[F]]
  addImplicit[ConcurrentEffect[F]]
  addImplicit[Timer[F]]
  addImplicit[ContextShift[F]]

  make[Blocker].from(Blocker.liftExecutionContext {
    ExecutionContext.fromExecutor(
      Executors.newCachedThreadPool(
        new ThreadFactory {
          def newThread(r: Runnable): Thread = {
            val th = new Thread(r)
            th.setName(s"blocking-thread-pool-${th.getId}")
            th.setDaemon(true)
            th
          }
        }
      )
    )
  })
}

object EffectModule {

  /**
    * Creates an EffectModule using TC instances from the implicit scope.
    */
  final def apply[F[_]: Parallel: ConcurrentEffect: ContextShift: Timer: TagK]: EffectModule[F] =
    new EffectModule[F]
}
