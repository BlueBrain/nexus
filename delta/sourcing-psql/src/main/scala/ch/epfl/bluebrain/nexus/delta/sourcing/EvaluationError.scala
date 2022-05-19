package ch.epfl.bluebrain.nexus.delta.sourcing

import scala.concurrent.duration.FiniteDuration

sealed abstract class EvaluationError extends Exception with Product with Serializable { self =>
  override def fillInStackTrace(): Throwable = self
}

object EvaluationError {

  final case class EvaluationTimeout[Command](value: Command, timeoutAfter: FiniteDuration) extends EvaluationError

}
