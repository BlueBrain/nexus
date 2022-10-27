package ch.epfl.bluebrain.nexus.delta.sourcing

import ch.epfl.bluebrain.nexus.delta.sourcing.EvaluationError.EvaluationTimeout
import ch.epfl.bluebrain.nexus.delta.sourcing.model.EntityType
import ch.epfl.bluebrain.nexus.delta.sourcing.state.State.EphemeralState
import monix.bio.IO

import scala.concurrent.duration.FiniteDuration

final case class EphemeralDefinition[Id, S <: EphemeralState, Command, Rejection](
    tpe: EntityType,
    evaluate: Command => IO[Rejection, S],
    stateSerializer: Serializer[Id, S],
    onUniqueViolation: (Id, Command) => Rejection
) {

  /**
    * Fetches the current state and attempt to apply an incoming command on it
    */
  def evaluate(command: Command, maxDuration: FiniteDuration): IO[Rejection, S] =
    evaluate(command).attempt
      .timeoutWith(maxDuration, EvaluationTimeout(command, maxDuration))
      .hideErrors
      .flatMap(IO.fromEither)

}
