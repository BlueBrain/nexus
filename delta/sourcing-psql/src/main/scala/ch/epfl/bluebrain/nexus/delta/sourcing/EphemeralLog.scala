package ch.epfl.bluebrain.nexus.delta.sourcing

import cats.effect.{ContextShift, IO, Timer}
import ch.epfl.bluebrain.nexus.delta.kernel.error.Rejection
import ch.epfl.bluebrain.nexus.delta.sourcing.config.EphemeralLogConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sourcing.state.EphemeralStateStore
import ch.epfl.bluebrain.nexus.delta.sourcing.state.State.EphemeralState
import doobie.implicits._
import doobie.postgres.sqlstate

/**
  * Event log for ephemeral entities that can be controlled through commands;
  *
  * Successful commands override the previous state. If we use a persistent implementation, new events are also appended
  * to the event log. No events are generated for this implementation
  *
  * Unsuccessful commands result in rejections returned to the caller context without any events being generated or
  * state transitions applied.
  */
trait EphemeralLog[Id, S <: EphemeralState, Command, R <: Rejection] {

  /**
    * Get the current state for the entity with the given __id__
    * @param ref
    *   the project the entity belongs in
    * @param id
    *   the entity identifier
    * @param notFound
    *   if no state is found, fails with this rejection
    */
  def stateOr[R2 <: R](ref: ProjectRef, id: Id, notFound: => R2): IO[S]

  /**
    * Evaluates the argument __command__ in the context of entity identified by __id__.
    *
    * @param ref
    *   the project the entity belongs in
    * @param id
    *   the entity identifier
    * @param command
    *   the command to evaluate
    * @return
    *   the newly generated state if the command was evaluated successfully, or the rejection of the __command__
    *   otherwise
    */
  def evaluate(ref: ProjectRef, id: Id, command: Command): IO[S]

}

object EphemeralLog {

  /**
    * Creates on a ephemeral log for the given definition and config
    */
  def apply[Id, S <: EphemeralState, Command, R <: Rejection](
      definition: EphemeralDefinition[Id, S, Command, R],
      config: EphemeralLogConfig,
      xas: Transactors
  )(implicit contextShift: ContextShift[IO], timer: Timer[IO]): EphemeralLog[Id, S, Command, R] = {
    val stateStore = EphemeralStateStore(definition.tpe, definition.stateSerializer, config.ttl, xas)
    new EphemeralLog[Id, S, Command, R] {

      override def stateOr[R2 <: R](ref: ProjectRef, id: Id, notFound: => R2): IO[S] =
        stateStore.get(ref, id).flatMap {
          IO.fromOption(_)(notFound)
        }

      override def evaluate(ref: ProjectRef, id: Id, command: Command): IO[S] = {
        for {
          newState <- definition.evaluate(command, config.maxDuration)
          res      <- stateStore
                        .save(newState)
                        .attemptSomeSqlState { case sqlstate.class23.UNIQUE_VIOLATION =>
                          definition.onUniqueViolation(id, command)
                        }
                        .transact(xas.write)
          _        <- IO.fromEither(res)
        } yield newState
      }
    }
  }

}
