package ch.epfl.bluebrain.nexus.delta.sourcing

import cats.effect.IO
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import ch.epfl.bluebrain.nexus.delta.sourcing.EvaluationError._
import ch.epfl.bluebrain.nexus.delta.sourcing.ScopedEntityDefinition.Tagger
import ch.epfl.bluebrain.nexus.delta.sourcing.config.EventLogConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.event.Event.ScopedEvent
import ch.epfl.bluebrain.nexus.delta.sourcing.event.ScopedEventStore
import ch.epfl.bluebrain.nexus.delta.sourcing.model.EntityDependency.DependsOn
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import ch.epfl.bluebrain.nexus.delta.sourcing.model._
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.state.ScopedStateStore
import ch.epfl.bluebrain.nexus.delta.sourcing.state.ScopedStateStore.StateNotFound.{TagNotFound, UnknownState}
import ch.epfl.bluebrain.nexus.delta.sourcing.state.State.ScopedState
import ch.epfl.bluebrain.nexus.delta.sourcing.tombstone.TombstoneStore
import doobie._
import doobie.implicits._
import doobie.postgres.sqlstate
import fs2.Stream

import java.sql.SQLException
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
    * Allow to stream all latest states within [[Elem.SuccessElem]] s without applying transformation
    * @param scope
    *   to filter returned states
    * @param offset
    *   offset to start from
    */
  def currentStates(scope: Scope, offset: Offset): SuccessElemStream[S]

  /**
    * Allow to stream all latest states from the beginning within [[Elem.SuccessElem]] s without applying transformation
    * @param scope
    *   to filter returned states
    */
  def currentStates(scope: Scope): SuccessElemStream[S] = currentStates(scope, Offset.Start)

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
  def states(scope: Scope, offset: Offset): SuccessElemStream[S]
}

object ScopedEventLog {

  private val logger = Logger[ScopedEventLog.type]

  private val noop: ConnectionIO[Unit] = ().pure[ConnectionIO]

  def apply[Id, S <: ScopedState, Command, E <: ScopedEvent, Rejection <: Throwable](
      definition: ScopedEntityDefinition[Id, S, Command, E, Rejection],
      config: EventLogConfig,
      xas: Transactors
  ): ScopedEventLog[Id, S, Command, E, Rejection] =
    apply(
      definition.tpe,
      ScopedEventStore(definition.tpe, definition.eventSerializer, config.queryConfig, xas),
      ScopedStateStore(definition.tpe, definition.stateSerializer, config.queryConfig, xas),
      definition.evaluator,
      definition.onUniqueViolation,
      definition.tagger,
      definition.extractDependencies,
      config.maxDuration,
      xas
    )

  def apply[Id, S <: ScopedState, Command, E <: ScopedEvent, Rejection <: Throwable](
      entityType: EntityType,
      eventStore: ScopedEventStore[Id, E],
      stateStore: ScopedStateStore[Id, S],
      evaluator: CommandEvaluator[S, Command, E],
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
        evaluator.stateMachine.computeState(eventStore.history(ref, id, rev)).flatMap {
          case Some(s) if s.rev == rev => IO.pure(s)
          case Some(s)                 => IO.raiseError(invalidRevision(rev, s.rev))
          case None                    => IO.raiseError(notFound)
        }

      override def evaluate(ref: ProjectRef, id: Id, command: Command): IO[(E, S)] = {

        def newTaggedState(event: E, state: S): IO[Option[(UserTag, S)]] =
          tagger.tagWhen(event) match {
            case Some((tag, rev)) if rev == state.rev =>
              IO.some(tag -> state)
            case Some((tag, rev))                     =>
              evaluator.stateMachine
                .computeState(eventStore.history(ref, id, Some(rev)))
                .flatTap {
                  case stateOpt if !stateOpt.exists(_.rev == rev) =>
                    IO.raiseError(EvaluationTagFailure(command, stateOpt.map(_.rev)))
                  case _                                          => IO.unit
                }
                .map(_.map(tag -> _))
            case None                                 => IO.none
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

          def queries(newTaggedState: Option[(UserTag, S)], init: PartitionInit) =
            for {
              _ <- TombstoneStore.save(entityType, original, newState)
              _ <- eventStore.save(event, init)
              _ <- stateStore.save(newState, init)
              _ <- newTaggedState.traverse { case (tag, taggedState) =>
                     stateStore.save(taggedState, tag, Noop)
                   }
              _ <- deleteTag(event, newState)
              _ <- updateDependencies(newState)
            } yield ()

          {
            for {
              init        <- PartitionInit(event.project, xas.cache)
              taggedState <- newTaggedState(event, newState)
              res         <- queries(taggedState, init).transact(xas.write)
              _           <- init.updateCache(xas.cache)
            } yield res
          }.recoverWith {
            case sql: SQLException if isUniqueViolation(sql) =>
              logger.error(sql)(
                s"A unique constraint violation occurred when persisting an event for  '$id' in project '$ref' and rev ${event.rev}."
              ) >>
                IO.raiseError(onUniqueViolation(id, command))
            case other                                       =>
              logger.error(other)(
                s"An error occurred when persisting an event for '$id' in project '$ref' and rev ${event.rev}."
              ) >>
                IO.raiseError(other)
          }
        }

        for {
          originalState <- stateStore.getWrite(ref, id).redeem(_ => None, Some(_))
          result        <- evaluator.evaluate(originalState, command, maxDuration)
          _             <- persist(result._1, originalState, result._2)
        } yield result
      }

      private def isUniqueViolation(sql: SQLException) =
        sql.getSQLState == sqlstate.class23.UNIQUE_VIOLATION.value

      override def dryRun(ref: ProjectRef, id: Id, command: Command): IO[(E, S)] =
        stateStore.getWrite(ref, id).redeem(_ => None, Some(_)).flatMap { state =>
          evaluator.evaluate(state, command, maxDuration)
        }

      override def currentStates(scope: Scope, offset: Offset): SuccessElemStream[S] =
        stateStore.currentStates(scope, offset)

      override def currentStates[T](scope: Scope, offset: Offset, f: S => T): Stream[IO, T] =
        currentStates(scope, offset).map { s =>
          f(s.value)
        }

      override def states(scope: Scope, offset: Offset): SuccessElemStream[S] =
        stateStore.states(scope, offset)
    }

}
