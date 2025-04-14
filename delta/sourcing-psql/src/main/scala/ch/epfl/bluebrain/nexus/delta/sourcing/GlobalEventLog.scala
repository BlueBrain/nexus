package ch.epfl.bluebrain.nexus.delta.sourcing

import cats.effect.IO
import cats.syntax.all.*
import ch.epfl.bluebrain.nexus.delta.sourcing.config.EventLogConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.event.Event.GlobalEvent
import ch.epfl.bluebrain.nexus.delta.sourcing.event.GlobalEventStore
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.state.GlobalStateStore
import ch.epfl.bluebrain.nexus.delta.sourcing.state.State.GlobalState
import doobie.syntax.all.*
import doobie.postgres.sqlstate
import fs2.Stream

import scala.concurrent.duration.FiniteDuration

/**
  * Event log for global entities that can be controlled through commands;
  *
  * Successful commands result in state transitions. Events are also appended to the event log.
  *
  * Unsuccessful commands result in rejections returned to the caller context without any events being generated or
  * state transitions applied.
  */
trait GlobalEventLog[Id, S <: GlobalState, Command, E <: GlobalEvent, Rejection <: Throwable] {

  /**
    * Get the current state for the entity with the given __id__
    * @param id
    *   the entity identifier
    * @param notFound
    *   if no state is found, fails with this rejection
    */
  def stateOr[R <: Rejection](id: Id, notFound: => R): IO[S]

  /**
    * Get the current state for the entity with the given __id__ at the given __revision__
    * @param id
    *   the entity identifier
    * @param rev
    *   the revision
    * @param notFound
    *   if no state is found, fails with this rejection
    * @param invalidRevision
    *   if the revision of the resulting state does not match with the one provided
    */
  def stateOr[R <: Rejection](id: Id, rev: Int, notFound: => R, invalidRevision: (Int, Int) => R): IO[S]

  /**
    * Evaluates the argument __command__ in the context of entity identified by __id__.
    *
    * @param id
    *   the entity identifier
    * @param command
    *   the command to evaluate
    * @return
    *   the newly generated state and appended event if the command was evaluated successfully, or the rejection of the
    *   __command__ otherwise
    */
  def evaluate(id: Id, command: Command): IO[(E, S)]

  /**
    * Tests the evaluation the argument __command__ in the context of entity identified by __id__, without applying any
    * changes to the state or event log of the entity regardless of the outcome of the command evaluation.
    *
    * @param id
    *   the entity identifier
    * @param command
    *   the command to evaluate
    * @return
    *   the state and event that would be generated in if the command was tested for evaluation successfully, or the
    *   rejection of the __command__ in otherwise
    */
  def dryRun(id: Id, command: Command): IO[(E, S)]

  /**
    * Delete both states and events for the given id
    * @param id
    */
  def delete(id: Id): IO[Unit]

  /**
    * Allow to stream all latest states from the provided offset
    * @param offset
    *   offset to start from
    * @param f
    *   the function to apply on each state
    */
  def currentStates[T](offset: Offset, f: S => T): Stream[IO, T]

  /**
    * Allow to stream all latest states from the beginning
    * @param f
    *   the function to apply on each state
    */
  final def currentStates[T](f: S => T): Stream[IO, T] = currentStates(Offset.Start, f)
}

object GlobalEventLog {

  def apply[Id, S <: GlobalState, Command, E <: GlobalEvent, Rejection <: Throwable](
      definition: GlobalEntityDefinition[Id, S, Command, E, Rejection],
      config: EventLogConfig,
      xas: Transactors
  ): GlobalEventLog[Id, S, Command, E, Rejection] =
    apply(
      GlobalEventStore(definition.tpe, definition.eventSerializer, config.queryConfig, xas),
      GlobalStateStore(definition.tpe, definition.stateSerializer, config.queryConfig, xas),
      definition.stateMachine,
      definition.onUniqueViolation,
      config.maxDuration,
      xas
    )

  def apply[Id, S <: GlobalState, Command, E <: GlobalEvent, Rejection <: Throwable](
      eventStore: GlobalEventStore[Id, E],
      stateStore: GlobalStateStore[Id, S],
      stateMachine: StateMachine[S, Command, E],
      onUniqueViolation: (Id, Command) => Rejection,
      maxDuration: FiniteDuration,
      xas: Transactors
  ): GlobalEventLog[Id, S, Command, E, Rejection] =
    new GlobalEventLog[Id, S, Command, E, Rejection] {

      override def stateOr[R <: Rejection](id: Id, notFound: => R): IO[S] = stateStore.get(id).flatMap {
        IO.fromOption(_)(notFound)
      }

      override def stateOr[R <: Rejection](id: Id, rev: Int, notFound: => R, invalidRevision: (Int, Int) => R): IO[S] =
        stateMachine.computeState(eventStore.history(id, rev)).flatMap {
          case Some(s) if s.rev == rev => IO.pure(s)
          case Some(s)                 => IO.raiseError(invalidRevision(rev, s.rev))
          case None                    => IO.raiseError(notFound)
        }

      override def evaluate(id: Id, command: Command): IO[(E, S)] =
        stateStore.getWrite(id).flatMap { current =>
          stateMachine
            .evaluate(current, command, maxDuration)
            .flatTap { case (event, state) =>
              (eventStore.save(event) >> stateStore.save(state))
                .attemptSomeSqlState { case sqlstate.class23.UNIQUE_VIOLATION =>
                  onUniqueViolation(id, command)
                }
                .transact(xas.write)
                .flatMap(IO.fromEither)
            }
        }

      override def dryRun(id: Id, command: Command): IO[(E, S)] =
        stateStore.getWrite(id).flatMap { current =>
          stateMachine.evaluate(current, command, maxDuration)
        }

      override def delete(id: Id): IO[Unit] =
        (stateStore.delete(id) >> eventStore.delete(id)).transact(xas.write)

      override def currentStates[T](offset: Offset, f: S => T): Stream[IO, T] =
        stateStore.currentStates(offset).map(f)
    }

}
