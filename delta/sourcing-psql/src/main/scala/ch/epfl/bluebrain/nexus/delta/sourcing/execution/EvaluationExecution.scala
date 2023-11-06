package ch.epfl.bluebrain.nexus.delta.sourcing.execution

import cats.effect.{ContextShift, IO, Timer}

final case class EvaluationExecution(timer: Timer[IO], contextShift: ContextShift[IO])
