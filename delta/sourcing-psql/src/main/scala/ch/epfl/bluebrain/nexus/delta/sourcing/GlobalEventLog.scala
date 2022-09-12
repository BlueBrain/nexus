package ch.epfl.bluebrain.nexus.delta.sourcing

import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.kernel.database.Transactors
import ch.epfl.bluebrain.nexus.delta.sourcing.EvaluationError.{EvaluationFailure, EvaluationTimeout}
import ch.epfl.bluebrain.nexus.delta.sourcing.config.EventLogConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.event.Event.GlobalEvent
import ch.epfl.bluebrain.nexus.delta.sourcing.event.GlobalEventStore
import ch.epfl.bluebrain.nexus.delta.sourcing.model.EnvelopeStream
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.state.GlobalStateStore
import ch.epfl.bluebrain.nexus.delta.sourcing.state.State.GlobalState
import doobie.Get
import doobie.implicits._
import doobie.postgres.sqlstate
import doobie.util.Put
import fs2.Stream
import monix.bio.Cause.{Error, Termination}
import monix.bio.{IO, Task}

import scala.concurrent.duration.FiniteDuration

/**
  * Event log for global entities that can be controlled through commands;
  *
  * Successful commands result in state transitions. If we use a persistent implementation, new events are also appended
  * to the event log.
  *
  * Unsuccessful commands result in rejections returned to the caller context without any events being generated or
  * state transitions applied.
  */
trait GlobalEventLog[Id, S <: GlobalState, Command, E <: GlobalEvent, Rejection] {

  /**
    * Get the current state for the entity with the given __id__
    * @param id
    *   the entity identifier
    * @param notFound
    *   if no state is found, fails with this rejection
    */
  def stateOr[R <: Rejection](id: Id, notFound: => R): IO[R, S]

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
  def stateOr[R <: Rejection](id: Id, rev: Int, notFound: => R, invalidRevision: (Int, Int) => R): IO[R, S]

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
  def evaluate(id: Id, command: Command): IO[Rejection, (E, S)]

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
  def dryRun(id: Id, command: Command): IO[Rejection, (E, S)]

  /**
    * Allow to stream all current events within [[Envelope]] s
    * @param offset
    *   offset to start from
    */
  def currentEvents(offset: Offset): EnvelopeStream[Id, E]

  /**
    * Allow to stream all current events within [[Envelope]] s
    * @param offset
    *   offset to start from
    */
  def events(offset: Offset): EnvelopeStream[Id, E]

  /**
    * Allow to stream all latest states within [[Envelope]] s without applying transformation
    * @param offset
    *   offset to start from
    */
  def currentStates(offset: Offset): EnvelopeStream[Id, S]

  /**
    * Allow to stream all latest states from the beginning within [[Envelope]] s without applying transformation
    */
  def currentStates: EnvelopeStream[Id, S] = currentStates(Offset.Start)

  /**
    * Allow to stream all latest states from the provided offset
    * @param offset
    *   offset to start from
    * @param f
    *   the function to apply on each state
    */
  def currentStates[T](offset: Offset, f: S => T): Stream[Task, T]

  /**
    * Allow to stream all latest states from the beginning
    * @param f
    *   the function to apply on each state
    */
  def currentStates[T](f: S => T): Stream[Task, T] = currentStates(Offset.Start, f)
}

object GlobalEventLog {

  def apply[Id, S <: GlobalState, Command, E <: GlobalEvent, Rejection](
      definition: GlobalEntityDefinition[Id, S, Command, E, Rejection],
      config: EventLogConfig,
      xas: Transactors
  )(implicit get: Get[Id], put: Put[Id]): GlobalEventLog[Id, S, Command, E, Rejection] =
    apply(
      GlobalEventStore(definition.tpe, definition.eventSerializer, config.queryConfig, xas),
      GlobalStateStore(definition.tpe, definition.stateSerializer, config.queryConfig, xas),
      definition.stateMachine,
      definition.onUniqueViolation,
      config.maxDuration,
      xas
    )

  def apply[Id, S <: GlobalState, Command, E <: GlobalEvent, Rejection](
      eventStore: GlobalEventStore[Id, E],
      stateStore: GlobalStateStore[Id, S],
      stateMachine: StateMachine[S, Command, E, Rejection],
      onUniqueViolation: (Id, Command) => Rejection,
      maxDuration: FiniteDuration,
      xas: Transactors
  ): GlobalEventLog[Id, S, Command, E, Rejection] = new GlobalEventLog[Id, S, Command, E, Rejection] {

    override def stateOr[R <: Rejection](id: Id, notFound: => R): IO[R, S] = stateStore.get(id).flatMap {
      IO.fromOption(_, notFound)
    }

    override def stateOr[R <: Rejection](id: Id, rev: Int, notFound: => R, invalidRevision: (Int, Int) => R): IO[R, S] =
      stateMachine.computeState(eventStore.history(id, rev)).flatMap {
        case Some(s) if s.rev == rev => IO.pure(s)
        case Some(s)                 => IO.raiseError(invalidRevision(rev, s.rev))
        case None                    => IO.raiseError(notFound)
      }

    override def evaluate(id: Id, command: Command): IO[Rejection, (E, S)] =
      stateStore.get(id).flatMap { current =>
        stateMachine
          .evaluate(current, command, maxDuration)
          .tapEval { case (event, state) =>
            (eventStore.save(event) >> stateStore.save(state))
              .attemptSomeSqlState { case sqlstate.class23.UNIQUE_VIOLATION =>
                onUniqueViolation(id, command)
              }
              .transact(xas.write)
              .hideErrors
              .flatMap(IO.fromEither)
          }
          .redeemCauseWith(
            {
              case Error(rejection)                     => IO.raiseError(rejection)
              case Termination(e: EvaluationTimeout[_]) => IO.terminate(e)
              case Termination(e)                       => IO.terminate(EvaluationFailure(command, e))
            },
            r => IO.pure(r)
          )
      }

    override def dryRun(id: Id, command: Command): IO[Rejection, (E, S)] =
      stateStore.get(id).flatMap { current =>
        stateMachine.evaluate(current, command, maxDuration)
      }

    override def currentEvents(offset: Offset): EnvelopeStream[Id, E] = eventStore.currentEvents(offset)

    override def events(offset: Offset): EnvelopeStream[Id, E] = eventStore.events(offset)

    override def currentStates[T](offset: Offset, f: S => T): Stream[Task, T] = currentStates(offset).map { e =>
      f(e.value)
    }

    override def currentStates(offset: Offset): EnvelopeStream[Id, S] = stateStore.currentStates(offset)

  }

}
