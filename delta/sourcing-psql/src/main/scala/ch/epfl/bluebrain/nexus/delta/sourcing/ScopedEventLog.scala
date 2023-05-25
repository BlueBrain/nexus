package ch.epfl.bluebrain.nexus.delta.sourcing

import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.kernel.database.Transactors
import ch.epfl.bluebrain.nexus.delta.sourcing.EvaluationError.{EvaluationFailure, EvaluationTimeout}
import ch.epfl.bluebrain.nexus.delta.sourcing.ScopedEntityDefinition.Tagger
import ch.epfl.bluebrain.nexus.delta.sourcing.config.EventLogConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.event.Event.ScopedEvent
import ch.epfl.bluebrain.nexus.delta.sourcing.event.ScopedEventStore
import ch.epfl.bluebrain.nexus.delta.sourcing.model.EntityDependency.DependsOn
import ch.epfl.bluebrain.nexus.delta.sourcing.model._
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.state.ScopedStateStore
import ch.epfl.bluebrain.nexus.delta.sourcing.state.ScopedStateStore.StateNotFound.{TagNotFound, UnknownState}
import ch.epfl.bluebrain.nexus.delta.sourcing.state.State.ScopedState
import ch.epfl.bluebrain.nexus.delta.sourcing.tombstone.TombstoneStore
import com.typesafe.scalalogging.Logger
import doobie._
import doobie.implicits._
import doobie.postgres.sqlstate
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
    * @param notFound
    *   if no state is found, fails with this rejection
    * @param invalidRevision
    *   if the revision of the resulting state does not match with the one provided
    */
  def stateOr[R <: Rejection](
      ref: ProjectRef,
      id: Id,
      rev: Int,
      notFound: => R,
      invalidRevision: (Int, Int) => R
  ): IO[R, S]

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
  def currentEvents(predicate: Predicate, offset: Offset): EnvelopeStream[E]

  /**
    * Allow to stream all current events within [[Envelope]] s
    * @param predicate
    *   to filter returned events
    * @param offset
    *   offset to start from
    */
  def events(predicate: Predicate, offset: Offset): EnvelopeStream[E]

  /**
    * Allow to stream all latest states within [[Envelope]] s without applying transformation
    * @param predicate
    *   to filter returned states
    * @param offset
    *   offset to start from
    */
  def currentStates(predicate: Predicate, offset: Offset): EnvelopeStream[S]

  /**
    * Allow to stream all latest states from the beginning within [[Envelope]] s without applying transformation
    * @param predicate
    *   to filter returned states
    */
  def currentStates(predicate: Predicate): EnvelopeStream[S] = currentStates(predicate, Offset.Start)

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

  /**
    * Stream the state changes continuously from the provided offset.
    * @param predicate
    *   to filter returned states
    * @param offset
    *   the start offset
    */
  def states(predicate: Predicate, offset: Offset): EnvelopeStream[S]
}

object ScopedEventLog {

  private val logger: Logger = Logger[ScopedEventLog.type]

  private val noop: ConnectionIO[Unit] = ().pure[ConnectionIO]

  def apply[Id, S <: ScopedState, Command, E <: ScopedEvent, Rejection](
      definition: ScopedEntityDefinition[Id, S, Command, E, Rejection],
      config: EventLogConfig,
      xas: Transactors
  ): ScopedEventLog[Id, S, Command, E, Rejection] =
    apply(
      definition.tpe,
      ScopedEventStore(definition.tpe, definition.eventSerializer, config.queryConfig, xas),
      ScopedStateStore(definition.tpe, definition.stateSerializer, config.queryConfig, xas),
      definition.stateMachine,
      definition.onUniqueViolation,
      definition.tagger,
      definition.extractDependencies,
      config.maxDuration,
      xas
    )

  def apply[Id, S <: ScopedState, Command, E <: ScopedEvent, Rejection](
      entityType: EntityType,
      eventStore: ScopedEventStore[Id, E],
      stateStore: ScopedStateStore[Id, S],
      stateMachine: StateMachine[S, Command, E, Rejection],
      onUniqueViolation: (Id, Command) => Rejection,
      tagger: Tagger[E],
      extractDependencies: S => Option[Set[DependsOn]],
      maxDuration: FiniteDuration,
      xas: Transactors
  ): ScopedEventLog[Id, S, Command, E, Rejection] =
    new ScopedEventLog[Id, S, Command, E, Rejection] {

      override def stateOr[R <: Rejection](ref: ProjectRef, id: Id, notFound: => R): IO[R, S] =
        stateStore.get(ref, id).mapError(_ => notFound)

      override def stateOr[R <: Rejection](
          ref: ProjectRef,
          id: Id,
          tag: Tag,
          notFound: => R,
          tagNotFound: => R
      ): IO[R, S] = stateStore.get(ref, id, tag).mapError {
        case UnknownState => notFound
        case TagNotFound  => tagNotFound
      }

      override def stateOr[R <: Rejection](
          ref: ProjectRef,
          id: Id,
          rev: Int,
          notFound: => R,
          invalidRevision: (Int, Int) => R
      ): IO[R, S] =
        stateMachine.computeState(eventStore.history(ref, id, rev)).flatMap {
          case Some(s) if s.rev == rev => IO.pure(s)
          case Some(s)                 => IO.raiseError(invalidRevision(rev, s.rev))
          case None                    => IO.raiseError(notFound)
        }

      override def evaluate(ref: ProjectRef, id: Id, command: Command): IO[Rejection, (E, S)] = {

        def saveTag(event: E, state: S): UIO[ConnectionIO[Unit]] =
          tagger.tagWhen(event).fold(UIO.pure(noop)) { case (tag, rev) =>
            if (rev == state.rev)
              UIO.pure(stateStore.save(state, tag, Noop))
            else
              stateMachine
                .computeState(eventStore.history(ref, id, Some(rev)))
                .map(_.fold(noop) { s => stateStore.save(s, tag, Noop) })
          }

        def deleteTag(event: E, state: S): ConnectionIO[Unit] = tagger.untagWhen(event).fold(noop) { tag =>
          stateStore.delete(ref, id, tag) >>
            TombstoneStore.save(entityType, state, tag)
        }

        def updateDependencies(state: S) =
          extractDependencies(state).fold(noop) { dependencies =>
            EntityDependencyStore.delete(ref, state.id) >> EntityDependencyStore.save(ref, state.id, dependencies)
          }

        def persist(event: E, original: Option[S], newState: S): IO[Rejection, Unit] = {

          def queries(tagQuery: ConnectionIO[Unit], init: PartitionInit) =
            for {
              _ <- TombstoneStore.save(entityType, original, newState)
              _ <- eventStore.save(event, init)
              _ <- stateStore.save(newState, init)
              _ <- tagQuery
              _ <- deleteTag(event, newState)
              _ <- updateDependencies(newState)
            } yield ()

          {
            for {
              init     <- PartitionInit(event.project, xas.cache)
              tagQuery <- saveTag(event, newState)
              res      <- queries(tagQuery, init)
                            .attemptSomeSqlState { case sqlstate.class23.UNIQUE_VIOLATION =>
                              onUniqueViolation(id, command)
                            }
                            .transact(xas.write)
              _        <- init.updateCache(xas.cache)
            } yield res
          }.hideErrors
            .flatMap {
              IO.fromEither(_).tapError { _ =>
                UIO.delay(
                  logger.info(s"An event for the '$id' in project '$ref' already exists for rev ${event.rev}.")
                )
              }
            }
        }

        for {
          originalState <- stateStore.get(ref, id).redeem(_ => None, Some(_))
          result        <- stateMachine.evaluate(originalState, command, maxDuration)
          _             <- persist(result._1, originalState, result._2)
        } yield result
      }.redeemCauseWith(
        {
          case Error(rejection)                     => IO.raiseError(rejection)
          case Termination(e: EvaluationTimeout[_]) => IO.terminate(e)
          case Termination(e)                       => IO.terminate(EvaluationFailure(command, e))
        },
        r => IO.pure(r)
      )

      override def dryRun(ref: ProjectRef, id: Id, command: Command): IO[Rejection, (E, S)] =
        stateStore.get(ref, id).redeem(_ => None, Some(_)).flatMap { state =>
          stateMachine.evaluate(state, command, maxDuration)
        }

      override def currentEvents(predicate: Predicate, offset: Offset): EnvelopeStream[E] =
        eventStore.currentEvents(predicate, offset)

      override def events(predicate: Predicate, offset: Offset): EnvelopeStream[E] =
        eventStore.events(predicate, offset)

      override def currentStates(predicate: Predicate, offset: Offset): EnvelopeStream[S] =
        stateStore.currentStates(predicate, offset)

      override def currentStates[T](predicate: Predicate, offset: Offset, f: S => T): Stream[Task, T] =
        currentStates(predicate, offset).map { s =>
          f(s.value)
        }

      override def states(predicate: Predicate, offset: Offset): EnvelopeStream[S] =
        stateStore.states(predicate, offset)
    }

}
