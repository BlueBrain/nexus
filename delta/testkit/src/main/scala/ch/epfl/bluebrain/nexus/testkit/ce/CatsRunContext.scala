package ch.epfl.bluebrain.nexus.testkit.ce

import cats.effect.{ContextShift, IO, Timer}

import scala.concurrent.ExecutionContext

trait CatsRunContext {

  implicit lazy val contextShift: ContextShift[IO] = IO.contextShift(ExecutionContext.global)
  implicit lazy val timer: Timer[IO]               = IO.timer(ExecutionContext.global)

}
