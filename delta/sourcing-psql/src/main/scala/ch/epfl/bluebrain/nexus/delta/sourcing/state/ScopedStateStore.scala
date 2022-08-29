package ch.epfl.bluebrain.nexus.delta.sourcing.state

import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.kernel.database.Transactors
import ch.epfl.bluebrain.nexus.delta.sourcing.config.QueryConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.Latest
import ch.epfl.bluebrain.nexus.delta.sourcing.model._
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.query.RefreshStrategy
import ch.epfl.bluebrain.nexus.delta.sourcing.state.ScopedStateStore.StateNotFound
import ch.epfl.bluebrain.nexus.delta.sourcing.state.ScopedStateStore.StateNotFound.{TagNotFound, UnknownState}
import ch.epfl.bluebrain.nexus.delta.sourcing.state.State.ScopedState
import ch.epfl.bluebrain.nexus.delta.sourcing.{Predicate, Serializer}
import doobie._
import doobie.implicits._
import doobie.postgres.circe.jsonb.implicits._
import doobie.postgres.implicits._
import io.circe.Json
import io.circe.syntax.EncoderOps
import monix.bio.IO

/**
  * Allows to save/fetch [[ScopeState]] from the database
  */
trait ScopedStateStore[Id, S <: ScopedState] {

  /**
    * Persist the state as latest
    */
  def save(state: S): ConnectionIO[Unit] = save(state, Latest)

  /**
    * Persist the state with the given tag
    */
  def save(state: S, tag: Tag): ConnectionIO[Unit]

  /**
    * Delete the state for the given tag
    */
  def delete(ref: ProjectRef, id: Id, tag: Tag): ConnectionIO[Unit]

  /**
    * Returns the latest state
    */
  def get(ref: ProjectRef, id: Id): IO[UnknownState, S]

  /**
    * Returns the state at the given tag
    */
  def get(ref: ProjectRef, id: Id, tag: Tag): IO[StateNotFound, S]

  /**
    * Fetches latest states from the given type from the beginning.
    *
    * The stream is completed when it reaches the end.
    * @param predicate
    *   to filter returned states
    */
  def currentStates(predicate: Predicate): EnvelopeStream[Id, S] =
    currentStates(predicate, Offset.Start)

  /**
    * Fetches states from the given type with the given tag from the beginning.
    *
    * The stream is completed when it reaches the end.
    * @param predicate
    *   to filter returned states
    * @param tag
    *   only states with this tag will be selected
    */
  def currentStates(predicate: Predicate, tag: Tag): EnvelopeStream[Id, S] =
    currentStates(predicate, tag, Offset.Start)

  /**
    * Fetches latest states from the given type from the provided offset.
    *
    * The stream is completed when it reaches the end.
    * @param predicate
    *   to filter returned states
    * @param offset
    *   the offset
    */
  def currentStates(predicate: Predicate, offset: Offset): EnvelopeStream[Id, S] =
    currentStates(predicate, Latest, offset)

  /**
    * Fetches states from the given type with the given tag from the provided offset.
    *
    * The stream is completed when it reaches the end.
    * @param predicate
    *   to filter returned states
    * @param tag
    *   only states with this tag will be selected
    * @param offset
    *   the offset
    */
  def currentStates(predicate: Predicate, tag: Tag, offset: Offset): EnvelopeStream[Id, S]

  /**
    * Fetches latest states from the given type from the beginning
    *
    * The stream is not completed when it reaches the end of the existing events, but it continues to push new events
    * when new events are persisted.
    *
    * @param predicate
    *   to filter returned states
    */
  def states(predicate: Predicate): EnvelopeStream[Id, S] =
    states(predicate, Latest, Offset.Start)

  /**
    * Fetches states from the given type with the given tag from the beginning
    *
    * The stream is not completed when it reaches the end of the existing events, but it continues to push new events
    * when new states are persisted.
    *
    * @param predicate
    *   to filter returned states
    * @param tag
    *   only states with this tag will be selected
    */
  def states(predicate: Predicate, tag: Tag): EnvelopeStream[Id, S] = states(predicate, tag, Offset.Start)

  /**
    * Fetches latest states from the given type from the provided offset
    *
    * The stream is not completed when it reaches the end of the existing events, but it continues to push new events
    * when new events are persisted.
    *
    * @param predicate
    *   to filter returned states
    * @param offset
    *   the offset
    */
  def states(predicate: Predicate, offset: Offset): EnvelopeStream[Id, S] =
    states(predicate, Latest, offset)

  /**
    * Fetches states from the given type with the given tag from the provided offset
    *
    * The stream is not completed when it reaches the end of the existing events, but it continues to push new events
    * when new states are persisted.
    *
    * @param predicate
    *   to filter returned states
    * @param tag
    *   only states with this tag will be selected
    * @param offset
    *   the offset
    */
  def states(predicate: Predicate, tag: Tag, offset: Offset): EnvelopeStream[Id, S]

}

object ScopedStateStore {

  sealed private[sourcing] trait StateNotFound extends Product with Serializable

  private[sourcing] object StateNotFound {
    sealed trait UnknownState      extends StateNotFound
    final case object UnknownState extends UnknownState
    sealed trait TagNotFound       extends StateNotFound
    final case object TagNotFound  extends TagNotFound
  }

  def apply[Id, S <: ScopedState](
      tpe: EntityType,
      serializer: Serializer[Id, S],
      config: QueryConfig,
      xas: Transactors
  )(implicit getId: Get[Id], putId: Put[Id]): ScopedStateStore[Id, S] = new ScopedStateStore[Id, S] {

    import serializer._

    override def save(state: S, tag: Tag): doobie.ConnectionIO[Unit] = {
      val id = extractId(state)
      sql"SELECT 1 FROM scoped_states WHERE type = $tpe AND org = ${state.organization} AND project = ${state.project.project}  AND id = $id AND tag = $tag"
        .query[Int]
        .option
        .flatMap {
          _.fold(sql"""
                      | INSERT INTO scoped_states (
                      |  type,
                      |  org,
                      |  project,
                      |  id,
                      |  tag,
                      |  rev,
                      |  value,
                      |  deprecated,
                      |  instant
                      | )
                      | VALUES (
                      |  $tpe,
                      |  ${state.organization},
                      |  ${state.project.project},
                      |  $id,
                      |  $tag,
                      |  ${state.rev},
                      |  ${state.asJson},
                      |  ${state.deprecated},
                      |  ${state.updatedAt}
                      | )
            """.stripMargin) { _ =>
            sql"""
                 | UPDATE scoped_states SET
                 |  rev = ${state.rev},
                 |  value = ${state.asJson},
                 |  deprecated = ${state.deprecated},
                 |  instant = ${state.updatedAt},
                 |  ordering = (select nextval('scoped_states_ordering_seq'))
                 | WHERE
                 |  type = $tpe AND
                 |  org = ${state.organization} AND
                 |  project = ${state.project.project} AND
                 |  id = $id AND
                 |  tag = $tag
            """.stripMargin
          }.update.run.void
        }
    }

    override def delete(ref: ProjectRef, id: Id, tag: Tag): ConnectionIO[Unit] =
      sql"""DELETE FROM scoped_states WHERE type = $tpe AND org = ${ref.organization} AND project = ${ref.project}  AND id = $id AND tag = $tag""".stripMargin.update.run.void

    private def getValue(ref: ProjectRef, id: Id, tag: Tag): ConnectionIO[Option[Json]] =
      sql"""SELECT value FROM scoped_states WHERE type = $tpe AND org = ${ref.organization} AND project = ${ref.project}  AND id = $id AND tag = $tag"""
        .query[Json]
        .option

    private def exists(ref: ProjectRef, id: Id): ConnectionIO[Boolean] =
      sql"""SELECT id FROM scoped_states WHERE type = $tpe AND org = ${ref.organization} AND project = ${ref.project} AND id = $id LIMIT 1"""
        .query[Id]
        .option
        .map(_.isDefined)

    override def get(ref: ProjectRef, id: Id): IO[UnknownState, S] =
      getValue(ref, id, Latest).transact(xas.read).hideErrors.flatMap {
        case Some(json) => IO.fromEither(json.as[S]).hideErrors
        case None       => IO.raiseError(UnknownState)
      }

    override def get(ref: ProjectRef, id: Id, tag: Tag): IO[StateNotFound, S] = {
      for {
        value  <- getValue(ref, id, tag)
        exists <- value.fold(exists(ref, id))(_ => true.pure[ConnectionIO])
      } yield value -> exists
    }.transact(xas.read).hideErrors.flatMap {
      case (Some(json), _) => IO.fromEither(json.as[S]).hideErrors
      case (None, true)    => IO.raiseError(TagNotFound)
      case (None, false)   => IO.raiseError(UnknownState)
    }

    private def states(
        predicate: Predicate,
        tag: Tag,
        offset: Offset,
        strategy: RefreshStrategy
    ): EnvelopeStream[Id, S] =
      Envelope.stream(
        offset,
        offset =>
          // format: off
          sql"""SELECT type, id, value, rev, instant, ordering FROM public.scoped_states
               |${Fragments.whereAndOpt(Some(fr"type = $tpe"), predicate.asFragment, Some(fr"tag = $tag"), offset.asFragment)}
               |ORDER BY ordering""".stripMargin.query[Envelope[Id, S]],
        xas,
        config.copy(refreshStrategy = strategy)
      )

    override def currentStates(predicate: Predicate, tag: Tag, offset: Offset): EnvelopeStream[Id, S] =
      states(predicate, tag, offset, RefreshStrategy.Stop)

    override def states(predicate: Predicate, tag: Tag, offset: Offset): EnvelopeStream[Id, S] =
      states(predicate, tag, offset, config.refreshStrategy)
  }

}
