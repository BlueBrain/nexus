package ch.epfl.bluebrain.nexus.testkit.ce

import cats.effect.{ContextShift, IO, Timer}

import scala.concurrent.ExecutionContext

trait CatsRunContext {

  implicit val contextShift: ContextShift[IO] = IO.contextShift(ExecutionContext.global)
  implicit val timer: Timer[IO]               = IO.timer(ExecutionContext.global)

}
