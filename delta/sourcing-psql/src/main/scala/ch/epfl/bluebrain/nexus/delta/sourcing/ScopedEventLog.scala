package ch.epfl.bluebrain.nexus.delta.sourcing

import cats.effect.{IO, Timer}
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.kernel.Logger
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
import ch.epfl.bluebrain.nexus.delta.kernel.effect.migration._
import doobie._
import doobie.implicits._
import doobie.postgres.sqlstate
import fs2.Stream

import scala.concurrent.duration.FiniteDuration
import scala.reflect.ClassTag

/**
  * Event log for project-scoped entities that can be controlled through commands;
  *
  * Successful commands result in state transitions. If we use a persistent implementation, new events are also appended
  * to the event log.
  *
  * Unsuccessful commands result in rejections returned to the caller context without any events being generated or
  * state transitions applied.
  */
trait ScopedEventLog[Id, S <: ScopedState, Command, E <: ScopedEvent, Rejection <: Throwable] {

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
  def stateOr[R <: Rejection](ref: ProjectRef, id: Id, notFound: => R): IO[S]

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
  def stateOr[R <: Rejection](ref: ProjectRef, id: Id, tag: Tag, notFound: => R, tagNotFound: => R): IO[S]

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
  ): IO[S]

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
  def evaluate(ref: ProjectRef, id: Id, command: Command): IO[(E, S)]

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
  def dryRun(ref: ProjectRef, id: Id, command: Command): IO[(E, S)]

  /**
    * Allow to stream all current events within [[Envelope]] s
    * @param scope
    *   to filter returned events
    * @param offset
    *   offset to start from
    */
  def currentEvents(scope: Scope, offset: Offset): EnvelopeStream[E]

  /**
    * Allow to stream all current events within [[Envelope]] s
    * @param scope
    *   to filter returned events
    * @param offset
    *   offset to start from
    */
  def events(scope: Scope, offset: Offset): EnvelopeStream[E]

  /**
    * Allow to stream all latest states within [[Envelope]] s without applying transformation
    * @param scope
    *   to filter returned states
    * @param offset
    *   offset to start from
    */
  def currentStates(scope: Scope, offset: Offset): EnvelopeStream[S]

  /**
    * Allow to stream all latest states from the beginning within [[Envelope]] s without applying transformation
    * @param scope
    *   to filter returned states
    */
  def currentStates(scope: Scope): EnvelopeStream[S] = currentStates(scope, Offset.Start)

  /**
    * Allow to stream all current states from the provided offset
    * @param scope
    *   to filter returned states
    * @param offset
    *   offset to start from
    * @param f
    *   the function to apply on each state
    */
  def currentStates[T](scope: Scope, offset: Offset, f: S => T): Stream[IO, T]

  /**
    * Allow to stream all current states from the beginning
    * @param scope
    *   to filter returned states
    * @param f
    *   the function to apply on each state
    */
  def currentStates[T](scope: Scope, f: S => T): Stream[IO, T] = currentStates(scope, Offset.Start, f)

  /**
    * Stream the state changes continuously from the provided offset.
    * @param scope
    *   to filter returned states
    * @param offset
    *   the start offset
    */
  def states(scope: Scope, offset: Offset): EnvelopeStream[S]
}

object ScopedEventLog {

  private val logger = Logger.cats[ScopedEventLog.type]

  private val noop: ConnectionIO[Unit] = ().pure[ConnectionIO]

  def apply[Id, S <: ScopedState, Command, E <: ScopedEvent, Rejection <: Throwable: ClassTag](
      definition: ScopedEntityDefinition[Id, S, Command, E, Rejection],
      config: EventLogConfig,
      xas: Transactors
  )(implicit timer: Timer[IO]): ScopedEventLog[Id, S, Command, E, Rejection] =
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

  def apply[Id, S <: ScopedState, Command, E <: ScopedEvent, Rejection <: Throwable: ClassTag](
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

      override def stateOr[R <: Rejection](ref: ProjectRef, id: Id, notFound: => R): IO[S] =
        stateStore.get(ref, id).adaptError(_ => notFound)

      override def stateOr[R <: Rejection](
          ref: ProjectRef,
          id: Id,
          tag: Tag,
          notFound: => R,
          tagNotFound: => R
      ): IO[S] = {
        stateStore.get(ref, id, tag).adaptError {
          case UnknownState => notFound
          case TagNotFound  => tagNotFound
        }
      }

      override def stateOr[R <: Rejection](
          ref: ProjectRef,
          id: Id,
          rev: Int,
          notFound: => R,
          invalidRevision: (Int, Int) => R
      ): IO[S] =
        stateMachine.computeState(eventStore.history(ref, id, rev).translate(ioToTaskK)).toCatsIO.flatMap {
          case Some(s) if s.rev == rev => IO.pure(s)
          case Some(s)                 => IO.raiseError(invalidRevision(rev, s.rev))
          case None                    => IO.raiseError(notFound)
        }

      override def evaluate(ref: ProjectRef, id: Id, command: Command): IO[(E, S)] = {

        def saveTag(event: E, state: S): IO[ConnectionIO[Unit]] =
          tagger.tagWhen(event).fold(IO.pure(noop)) { case (tag, rev) =>
            if (rev == state.rev)
              IO.pure(stateStore.save(state, tag, Noop))
            else
              stateMachine
                .computeState(eventStore.history(ref, id, Some(rev)).translate(ioToTaskK))
                .toCatsIO
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

        def persist(event: E, original: Option[S], newState: S): IO[Unit] = {

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
              init     <- PartitionInit(event.project, xas.cache).toCatsIO
              tagQuery <- saveTag(event, newState)
              res      <- queries(tagQuery, init)
                            .attemptSomeSqlState { case sqlstate.class23.UNIQUE_VIOLATION =>
                              onUniqueViolation(id, command)
                            }
                            .transact(xas.writeCE)
              _        <- init.updateCache(xas.cache)
            } yield res
          }
            .flatMap {
              IO.fromEither(_).onError { _ =>
                logger.info(s"An event for the '$id' in project '$ref' already exists for rev ${event.rev}.")
              }
            }
        }

        for {
          originalState <- stateStore.get(ref, id).redeem(_ => None, Some(_))
          result        <- stateMachine.evaluate(originalState, command, maxDuration).toCatsIO
          _             <- persist(result._1, originalState, result._2)
        } yield result
      }.adaptError {
        case e: Rejection            => e
        case e: EvaluationTimeout[_] => e
        case e                       => EvaluationFailure(command, e)
      }

      override def dryRun(ref: ProjectRef, id: Id, command: Command): IO[(E, S)] =
        stateStore.get(ref, id).redeem(_ => None, Some(_)).flatMap { state =>
          stateMachine.evaluate(state, command, maxDuration).toCatsIO
        }

      override def currentEvents(scope: Scope, offset: Offset): EnvelopeStream[E] =
        eventStore.currentEvents(scope, offset)

      override def events(scope: Scope, offset: Offset): EnvelopeStream[E] =
        eventStore.events(scope, offset)

      override def currentStates(scope: Scope, offset: Offset): EnvelopeStream[S] =
        stateStore.currentStates(scope, offset)

      override def currentStates[T](scope: Scope, offset: Offset, f: S => T): Stream[IO, T] =
        currentStates(scope, offset).map { s =>
          f(s.value)
        }

      override def states(scope: Scope, offset: Offset): EnvelopeStream[S] =
        stateStore.states(scope, offset)
    }

}
