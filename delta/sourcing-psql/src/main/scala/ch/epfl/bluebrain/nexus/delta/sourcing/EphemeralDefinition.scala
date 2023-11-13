package ch.epfl.bluebrain.nexus.delta.sourcing

import cats.effect.{ContextShift, IO, Timer}
import cats.implicits.catsSyntaxMonadErrorRethrow
import ch.epfl.bluebrain.nexus.delta.kernel.error.Rejection
import ch.epfl.bluebrain.nexus.delta.sourcing.EvaluationError.EvaluationTimeout
import ch.epfl.bluebrain.nexus.delta.sourcing.model.EntityType
import ch.epfl.bluebrain.nexus.delta.sourcing.state.State.EphemeralState

import scala.concurrent.duration.FiniteDuration

final case class EphemeralDefinition[Id, S <: EphemeralState, Command, +R <: Rejection](
    tpe: EntityType,
    evaluate: Command => IO[S],
    stateSerializer: Serializer[Id, S],
    onUniqueViolation: (Id, Command) => R
) {

  /**
    * Fetches the current state and attempt to apply an incoming command on it
    */
  def evaluate(command: Command, maxDuration: FiniteDuration)(implicit
      contextShift: ContextShift[IO],
      timer: Timer[IO]
  ): IO[S] =
    evaluate(command).attempt
      .timeoutTo(maxDuration, IO.raiseError(EvaluationTimeout(command, maxDuration)))
      .rethrow
}
