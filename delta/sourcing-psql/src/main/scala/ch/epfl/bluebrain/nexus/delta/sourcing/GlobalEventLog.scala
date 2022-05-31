package ch.epfl.bluebrain.nexus.delta.sourcing

import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.sourcing.EvaluationError.{EvaluationFailure, EvaluationTimeout}
import ch.epfl.bluebrain.nexus.delta.sourcing.config.SourcingConfig.EvaluationConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.event.Event.GlobalEvent
import ch.epfl.bluebrain.nexus.delta.sourcing.event.GlobalEventStore
import ch.epfl.bluebrain.nexus.delta.sourcing.state.GlobalStateStore
import ch.epfl.bluebrain.nexus.delta.sourcing.state.State.GlobalState
import doobie.implicits._
import doobie.postgres.sqlstate
import monix.bio.Cause.{Error, Termination}
import monix.bio.{IO, UIO}

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
    */
  def state(id: Id): UIO[Option[S]]

  /**
    * Get the current state for the entity with the given __id__ at the given __revision__
    * @param id
    *   the entity identifier
    * @param rev
    *   the revision
    */
  def state(id: Id, rev: Option[Int]): UIO[Option[S]]

  /**
    * Get the current state for the entity with the given __id__ at the given __revision__
    * @param id
    *   the entity identifier
    * @param rev
    *   the revision
    */
  def state(id: Id, rev: Int): UIO[Option[S]] = state(id, Some(rev))

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

}

object GlobalEventLog {

  def apply[Id, S <: GlobalState, Command, E <: GlobalEvent, Rejection](
      eventStore: GlobalEventStore[Id, E],
      stateStore: GlobalStateStore[Id, S],
      stateMachine: StateMachine[S, Command, E, Rejection],
      onUniqueViolation: (Id, Command) => Rejection,
      evaluationConfig: EvaluationConfig,
      xas: Transactors
  ): GlobalEventLog[Id, S, Command, E, Rejection] = new GlobalEventLog[Id, S, Command, E, Rejection] {

    override def state(id: Id): UIO[Option[S]] = stateStore.get(id)

    override def state(id: Id, rev: Option[Int]): UIO[Option[S]] =
      stateMachine.computeState(eventStore.history(id, rev))

    override def evaluate(id: Id, command: Command): IO[Rejection, (E, S)] =
      stateMachine.evaluate(stateStore.get(id), command, evaluationConfig).tapEval { case (event, state) =>
        (eventStore.save(event) >> stateStore.save(state))
          .attemptSomeSqlState { case sqlstate.class23.UNIQUE_VIOLATION =>
            onUniqueViolation(id, command)
          }
          .transact(xas.write)
          .hideErrors
          .flatMap(IO.fromEither)
      }.redeemCauseWith(
        {
          case Error(rejection) => IO.raiseError(rejection)
          case Termination(e: EvaluationTimeout[_]) => IO.terminate(e)
          case Termination(e) => IO.terminate(EvaluationFailure(command, e))
        },
        r => IO.pure(r)
      )

    override def dryRun(id: Id, command: Command): IO[Rejection, (E, S)] =
      stateMachine.evaluate(stateStore.get(id), command, evaluationConfig)
  }

}
