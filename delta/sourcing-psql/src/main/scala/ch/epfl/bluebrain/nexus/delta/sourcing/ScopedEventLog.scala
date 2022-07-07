package ch.epfl.bluebrain.nexus.delta.sourcing

import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.kernel.Transactors
import ch.epfl.bluebrain.nexus.delta.sourcing.EntityDefinition.Tagger
import ch.epfl.bluebrain.nexus.delta.sourcing.EvaluationError.{EvaluationFailure, EvaluationTimeout}
import ch.epfl.bluebrain.nexus.delta.sourcing.config.EventLogConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.event.Event.ScopedEvent
import ch.epfl.bluebrain.nexus.delta.sourcing.event.ScopedEventStore
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{EnvelopeStream, ProjectRef, Tag}
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.state.ScopedStateStore
import ch.epfl.bluebrain.nexus.delta.sourcing.state.ScopedStateStore.StateNotFound.{TagNotFound, UnknownState}
import ch.epfl.bluebrain.nexus.delta.sourcing.state.State.ScopedState
import doobie.implicits._
import doobie.postgres.sqlstate
import doobie.util.Put
import doobie.{ConnectionIO, Get}
import fs2.Stream
import monix.bio.Cause.{Error, Termination}
import monix.bio.{IO, Task, UIO}

import scala.concurrent.duration.FiniteDuration

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
    * Get the latest state for the entity with the given __id__ in the given project
    *
    * @param ref
    *   the project the entity belongs in
    * @param id
    *   the entity identifier
    * @param notFound
    *   if no state is found, fails with this rejection
    */
  def stateOr[R <: Rejection](ref: ProjectRef, id: Id, notFound: => R): IO[R, S]

  /**
    * Get the state for the entity with the given __id__ at the given __tag__ in the given project
    * @param ref
    *   the project the entity belongs in
    * @param id
    *   the entity identifier
    * @param tag
    *   the tag
    * @param notFound
    *   if no state is found, fails with this rejection
    * @param tagNotFound
    *   if no state is found with the provided tag, fails with this rejection
    */
  def stateOr[R <: Rejection](ref: ProjectRef, id: Id, tag: Tag, notFound: => R, tagNotFound: => R): IO[R, S]

  /**
    * Get the state for the entity with the given __id__ at the given __revision__ in the given project
    * @param ref
    *   the project the entity belongs in
    * @param id
    *   the entity identifier
    * @param rev
    *   the revision
    *  @param notFound
    *   if no state is found, fails with this rejection
    *@param invalidRevision
    *   if the revision of the resulting state does not match with the one provided
    */
  def stateOr[R <: Rejection](ref: ProjectRef, id: Id, rev: Int, notFound: => R, invalidRevision: (Int, Int) => R): IO[R, S]

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

  /**
    * Allow to stream all current events within [[Envelope]] s
    * @param predicate
    *   to filter returned events
    * @param offset
    *   offset to start from
    */
  def currentEvents(predicate: Predicate, offset: Offset): EnvelopeStream[Id, E]

  /**
    * Allow to stream all current events within [[Envelope]] s
    * @param predicate
    *   to filter returned events
    * @param offset
    *   offset to start from
    */
  def events(predicate: Predicate, offset: Offset): EnvelopeStream[Id, E]

  /**
    * Allow to stream all latest states within [[Envelope]] s without applying transformation
    * @param predicate
    *   to filter returned states
    * @param offset
    *   offset to start from
    */
  def currentStates(predicate: Predicate, offset: Offset): EnvelopeStream[Id, S]

  /**
    * Allow to stream all latest states from the beginning within [[Envelope]] s without applying transformation
    * @param predicate
    *   to filter returned states
    */
  def currentStates(predicate: Predicate): EnvelopeStream[Id, S] = currentStates(predicate, Offset.Start)

  /**
    * Allow to stream all current states from the provided offset
    * @param predicate
    *   to filter returned states
    * @param offset
    *   offset to start from
    * @param f
    *   the function to apply on each state
    */
  def currentStates[T](predicate: Predicate, offset: Offset, f: S => T): Stream[Task, T]

  /**
    * Allow to stream all current states from the beginning
    * @param predicate
    *   to filter returned states
    * @param f
    *   the function to apply on each state
    */
  def currentStates[T](predicate: Predicate, f: S => T): Stream[Task, T] = currentStates(predicate, Offset.Start, f)
}

object ScopedEventLog {

  private val noop: ConnectionIO[Unit] = ().pure[ConnectionIO]

  def apply[Id, S <: ScopedState, Command, E <: ScopedEvent, Rejection](
                                                                         definition: EntityDefinition[Id, S, Command, E, Rejection],
                                                                         config: EventLogConfig,
                                                                         xas: Transactors
                                                                       )(implicit get: Get[Id], put: Put[Id]): ScopedEventLog[Id, S, Command, E, Rejection] =
    apply(
      ScopedEventStore(definition.tpe, definition.eventSerializer, config.queryConfig, xas),
      ScopedStateStore(definition.tpe, definition.stateSerializer, config.queryConfig, xas),
      definition.stateMachine,
      definition.onUniqueViolation,
      definition.tagger,
      config.maxDuration,
      xas
    )

  def apply[Id, S <: ScopedState, Command, E <: ScopedEvent, Rejection](
      eventStore: ScopedEventStore[Id, E],
      stateStore: ScopedStateStore[Id, S],
      stateMachine: StateMachine[S, Command, E, Rejection],
      onUniqueViolation: (Id, Command) => Rejection,
      tagger: Tagger[E],
      maxDuration: FiniteDuration,
      xas: Transactors
  ): ScopedEventLog[Id, S, Command, E, Rejection] = new ScopedEventLog[Id, S, Command, E, Rejection] {

    override def stateOr[R <: Rejection](ref: ProjectRef, id: Id, notFound: => R): IO[R, S] =
      stateStore.get(ref, id).mapError(_ => notFound)

    override def stateOr[R <: Rejection](ref: ProjectRef,
                                         id: Id,
                                         tag: Tag,
                                         notFound: => R, tagNotFound: => R): IO[R, S] = stateStore.get(ref, id, tag).mapError {
      case UnknownState => notFound
      case TagNotFound  => tagNotFound
    }

    override def stateOr[R <: Rejection](ref: ProjectRef, id: Id, rev: Int, notFound: => R, invalidRevision: (Int, Int) => R): IO[R, S] =
      stateMachine.computeState(eventStore.history(ref, id, rev)).flatMap {
        case Some(s) if s.rev == rev => IO.pure(s)
        case Some(s)                 => IO.raiseError(invalidRevision(rev, s.rev))
        case None                    => IO.raiseError(notFound)
      }

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

      stateMachine
        .evaluate(stateStore.get(ref, id).redeem(_ => None, Some(_)), command, maxDuration)
        .tapEval { case (event, state) =>
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

    override def dryRun(ref: ProjectRef, id: Id, command: Command): IO[Rejection, (E, S)] =
      stateMachine.evaluate(stateStore.get(ref, id).redeem(_ => None, Some(_)), command, maxDuration)

    override def currentEvents(predicate: Predicate, offset: Offset): EnvelopeStream[Id, E] = eventStore.currentEvents(predicate, offset)

    override def events(predicate: Predicate, offset: Offset): EnvelopeStream[Id, E] = eventStore.events(predicate, offset)

    override def currentStates(predicate: Predicate, offset: Offset): EnvelopeStream[Id, S] = stateStore.currentStates(predicate, offset)

    override def currentStates[T](predicate: Predicate, offset: Offset, f: S => T): Stream[Task, T] = currentStates(predicate, offset).map { s =>
      f(s.value)
    }
  }

}
