package ch.epfl.bluebrain.nexus.delta.sourcing

import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.sourcing.EntityDefinition.Tagger
import ch.epfl.bluebrain.nexus.delta.sourcing.EvaluationError.{EvaluationFailure, EvaluationTimeout}
import ch.epfl.bluebrain.nexus.delta.sourcing.config.SourcingConfig.EvaluationConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.event.Event.ScopedEvent
import ch.epfl.bluebrain.nexus.delta.sourcing.event.ScopedEventStore
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.Latest
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{ProjectRef, Tag}
import ch.epfl.bluebrain.nexus.delta.sourcing.state.ScopedStateStore
import ch.epfl.bluebrain.nexus.delta.sourcing.state.State.ScopedState
import doobie.ConnectionIO
import doobie.implicits._
import doobie.postgres.sqlstate
import monix.bio.Cause.{Error, Termination}
import monix.bio.{IO, UIO}

/**
  * Event log for project-scoped entities that can be controlled through commands;
  *
  * Successful commands result in state transitions. If we use a persistent implementation, new events are also appended
  * to the event log.
  *
  * Unsuccessful commands result in rejections returned to the caller context without any events being generated or
  * state transitions applied.
  */
trait ScopedEventLog[Id, S <: ScopedState, Command, E <: ScopedEvent, Rejection] {

  /**
    * Get the current state for the entity with the given __id__ in the given project
    *
    * @param ref
    *   the project the entity belongs in
    * @param id
    *   the entity identifier
    */
  def state(ref: ProjectRef, id: Id): UIO[Option[S]] = state(ref, id, Latest)

  /**
    * Get the current state for the entity with the given __id__ at the given __tag__ in the given project
    * @param ref
    *   the project the entity belongs in
    * @param id
    *   the entity identifier
    * @param tag
    *   the tag
    */
  def state(ref: ProjectRef, id: Id, tag: Tag): UIO[Option[S]]

  /**
    * Get the current state for the entity with the given __id__ at the given __revision__ in the given project
    * @param ref
    *   the project the entity belongs in
    * @param id
    *   the entity identifier
    * @param rev
    *   the revision
    */
  def state(ref: ProjectRef, id: Id, rev: Option[Int]): UIO[Option[S]]

  /**
    * Get the current state for the entity with the given __id__ at the given __revision__ in the given project
    * @param ref
    *   the project the entity belongs in
    * @param id
    *   the entity identifier
    * @param rev
    *   the revision
    */
  def state(ref: ProjectRef, id: Id, rev: Int): UIO[Option[S]] = state(ref, id, Some(rev))

  /**
    * Evaluates the argument __command__ in the context of entity identified by __id__.
    * @param ref
    *   the project the entity belongs in
    * @param id
    *   the entity identifier
    * @param command
    *   the command to evaluate
    * @return
    *   the newly generated state and appended event if the command was evaluated successfully, or the rejection of the
    *   __command__ otherwise
    */
  def evaluate(ref: ProjectRef, id: Id, command: Command): IO[Rejection, (E, S)]

  /**
    * Tests the evaluation the argument __command__ in the context of entity identified by __id__, without applying any
    * changes to the state or event log of the entity regardless of the outcome of the command evaluation.
    *
    * @param ref
    *   the project the entity belongs in
    * @param id
    *   the entity identifier
    * @param command
    *   the command to evaluate
    * @return
    *   the state and event that would be generated in if the command was tested for evaluation successfully, or the
    *   rejection of the __command__ in otherwise
    */
  def dryRun(ref: ProjectRef, id: Id, command: Command): IO[Rejection, (E, S)]

}

object ScopedEventLog {

  private val noop: ConnectionIO[Unit] = ().pure[ConnectionIO]

  def apply[Id, S <: ScopedState, Command, E <: ScopedEvent, Rejection](
      eventStore: ScopedEventStore[Id, E],
      stateStore: ScopedStateStore[Id, S],
      stateMachine: StateMachine[S, Command, E, Rejection],
      onUniqueViolation: (Id, Command) => Rejection,
      tagger: Tagger[E],
      evaluationConfig: EvaluationConfig,
      xas: Transactors
  ): ScopedEventLog[Id, S, Command, E, Rejection] = new ScopedEventLog[Id, S, Command, E, Rejection] {

    override def state(ref: ProjectRef, id: Id, tag: Tag): UIO[Option[S]] = stateStore.get(ref, id, tag)

    override def state(ref: ProjectRef, id: Id, rev: Option[Int]): UIO[Option[S]] =
      stateMachine.computeState(eventStore.history(ref, id, rev))

    override def evaluate(ref: ProjectRef, id: Id, command: Command): IO[Rejection, (E, S)] = {

      def saveTag(event: E, state: S): UIO[ConnectionIO[Unit]] =
        tagger.tagWhen(event).fold(UIO.pure(noop)) { case (tag, rev) =>
          if (rev == state.rev)
            UIO.pure(stateStore.save(state, tag))
          else
            stateMachine
              .computeState(eventStore.history(ref, id, Some(rev)))
              .map(_.fold(noop) { s => stateStore.save(s, tag) })
        }

      def deleteTag(event: E): ConnectionIO[Unit] = tagger.untagWhen(event).fold(noop) { tag =>
        stateStore.delete(ref, id, tag)
      }

      stateMachine.evaluate(stateStore.get(ref, id), command, evaluationConfig).tapEval { case (event, state) =>
        for {
          tagQuery      <- saveTag(event, state)
          deleteTagQuery = deleteTag(event)
          _             <- (
                             eventStore.save(event) >>
                               stateStore.save(state) >>
                               tagQuery >> deleteTagQuery
                           ).attemptSomeSqlState { case sqlstate.class23.UNIQUE_VIOLATION =>
                             onUniqueViolation(id, command)
                           }.transact(xas.write)
                             .hideErrors
        } yield ()
      }.redeemCauseWith(
        {
          case Error(rejection) => IO.raiseError(rejection)
          case Termination(e: EvaluationTimeout[_]) => IO.terminate(e)
          case Termination(e) => IO.terminate(EvaluationFailure(command, e))
        },
        r => IO.pure(r)
      )
    }

    /**
      * Tests the evaluation the argument __command__ in the context of the entity, without applying any changes to the
      * state or event log of the entity regardless of the outcome of the command evaluation.
      *
      * @param ref
      *   the project the entity belongs in
      * @param id
      *   the entity identifier
      * @param command
      *   the command to evaluate
      * @return
      *   the state and event that would be generated in if the command was tested for evaluation successfully, or the
      *   rejection of the __command__ in otherwise
      */
    override def dryRun(ref: ProjectRef, id: Id, command: Command): IO[Rejection, (E, S)] =
      stateMachine.evaluate(stateStore.get(ref, id), command, evaluationConfig)
  }

}
