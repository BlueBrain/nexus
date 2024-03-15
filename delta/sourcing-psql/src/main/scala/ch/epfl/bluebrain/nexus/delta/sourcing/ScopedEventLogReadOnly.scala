package ch.epfl.bluebrain.nexus.delta.sourcing

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.sourcing.model._
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.state.State.ScopedState
import fs2.Stream

/**
  * Read-only event log for project-scoped entities
  */
trait ScopedEventLogReadOnly[Id, S <: ScopedState, Rejection <: Throwable] {

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
