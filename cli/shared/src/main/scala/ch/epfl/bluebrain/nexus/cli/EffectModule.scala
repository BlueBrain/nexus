package ch.epfl.bluebrain.nexus.cli

import cats.effect.{Async, Bracket, ConcurrentEffect, ContextShift, Effect, Sync, Timer}
import distage.{ModuleDef, TagK}

/**
  * Module definition that binds effect TC instances for an arbitrary F[_].
  */
class EffectModule[F[_]: ConcurrentEffect: ContextShift: Timer: TagK] extends ModuleDef {
  addImplicit[ConcurrentEffect[F]]
  addImplicit[Effect[F]]
  addImplicit[Async[F]]
  addImplicit[Sync[F]]
  addImplicit[Bracket[F, Throwable]]
  addImplicit[Timer[F]]
  addImplicit[ContextShift[F]]
}

object EffectModule {

  /**
    * Creates an EffectModule using TC instances from the implicit scope.
    */
  def apply[F[_]: ConcurrentEffect: ContextShift: Timer: TagK]: EffectModule[F] = new EffectModule[F]
}
