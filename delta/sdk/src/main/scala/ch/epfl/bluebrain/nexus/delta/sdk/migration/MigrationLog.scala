package ch.epfl.bluebrain.nexus.delta.sdk.migration

import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.kernel.database.Transactors
import ch.epfl.bluebrain.nexus.delta.sourcing.EvaluationError.InvalidState
import ch.epfl.bluebrain.nexus.delta.sourcing.config.EventLogConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.event.Event.{GlobalEvent, ScopedEvent}
import ch.epfl.bluebrain.nexus.delta.sourcing.event.{GlobalEventStore, ScopedEventStore}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.EntityType
import ch.epfl.bluebrain.nexus.delta.sourcing.state.State.{GlobalState, ScopedState}
import ch.epfl.bluebrain.nexus.delta.sourcing.state.{GlobalStateStore, ScopedStateStore}
import ch.epfl.bluebrain.nexus.delta.sourcing.tombstone.TombstoneStore
import ch.epfl.bluebrain.nexus.delta.sourcing.{EntityDependencyStore, GlobalEntityDefinition, Noop, PartitionInit, ScopedEntityDefinition}
import com.typesafe.scalalogging.Logger
import doobie.ConnectionIO
import doobie.implicits._
import doobie.postgres.sqlstate
import io.circe.Json
import monix.bio.{IO, Task, UIO}

trait MigrationLog {

  def entityType: EntityType

  def apply(event: ToMigrateEvent): Task[Unit]

}

object MigrationLog {

  private val logger: Logger = Logger[MigrationLog.type]

  def global[Id, S <: GlobalState, Command, E <: GlobalEvent, Rejection](
      definition: GlobalEntityDefinition[Id, S, Command, E, Rejection],
      extractId: E => Id,
      enrichJson: Json => Json,
      enrich: (E, Option[S]) => E,
      config: EventLogConfig,
      xas: Transactors
  ): MigrationLog = {
    val eventStore = GlobalEventStore(definition.tpe, definition.eventSerializer, config.queryConfig, xas)
    val stateStore = GlobalStateStore(definition.tpe, definition.stateSerializer, config.queryConfig, xas)

    import definition._

    new MigrationLog {
      override def entityType: EntityType = definition.tpe

      def append(event: E): Task[(E, S)] =
        for {
          original <- stateStore.get(extractId(event))
          enriched  = enrich(event, original)
          newState <- IO.fromOption(stateMachine.next(original, enriched), InvalidState(original, enriched))
          result   <- (eventStore.save(enriched) >> stateStore.save(newState))
                        .attemptSomeSqlState { case sqlstate.class23.UNIQUE_VIOLATION =>
                          "Fail"
                        }
                        .transact(xas.write)
          _        <- result.fold(
                        _ =>
                          Task.delay(
                            logger.error(
                              s"A unique violation has been raised for ${event.id}:${event.rev} of type $entityType"
                            )
                          ),
                        _ => Task.delay(logger.debug(s"Event and state for {}:{}of type {}", event.id, event.rev, entityType))
                      )
        } yield (enriched, newState)

      override def apply(migrate: ToMigrateEvent): Task[Unit] =
        for {
          _     <- Task.delay(logger.debug(s"[{} MigrationLog (Global)]", entityType))
          event <- Task.fromEither(definition.eventSerializer.codec.decodeJson(enrichJson(migrate.payload)))
          _     <-
            Task.delay(logger.debug(s"[{} MigrationLog (Global)] Will try to append the global event", entityType))
          _     <- Task.delay(logger.debug(s"[{} MigrationLog (Global)] Event info: {}", entityType, event.id))
          _     <- append(event)
        } yield ()
    }
  }

  def scoped[Id, S <: ScopedState, Command, E <: ScopedEvent, Rejection](
      definition: ScopedEntityDefinition[Id, S, Command, E, Rejection],
      extractId: E => Id,
      enrichJson: Json => Json,
      enrich: (E, Option[S]) => E,
      config: EventLogConfig,
      xas: Transactors
  ): MigrationLog = {
    val eventStore               = ScopedEventStore(definition.tpe, definition.eventSerializer, config.queryConfig, xas)
    val stateStore               = ScopedStateStore(definition.tpe, definition.stateSerializer, config.queryConfig, xas)
    val noop: ConnectionIO[Unit] = ().pure[ConnectionIO]

    import definition._

    new MigrationLog {
      override def entityType: EntityType = definition.tpe

      def append(event: E): Task[(E, S)] = {
        val ref                                                  = event.project
        val id                                                   = extractId(event)
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

        def persist(event: E, original: Option[S], newState: S, init: PartitionInit) =
          saveTag(event, newState)
            .flatMap { tagQuery =>
              val queries = for {
                _ <- TombstoneStore.save(entityType, original, newState)
                _ <- eventStore.save(event, init)
                _ <- stateStore.save(newState, init)
                _ <- tagQuery
                _ <- deleteTag(event, newState)
                _ <- updateDependencies(newState)
              } yield ()
              queries
                .attemptSomeSqlState { case sqlstate.class23.UNIQUE_VIOLATION =>
                  "Fail"
                }
                .transact(xas.write)
            }

        for {
          init     <- PartitionInit(event.project, xas.cache)
          original <- stateStore.get(event.project, extractId(event)).redeem(_ => None, Some(_))
          enriched  = enrich(event, original)
          newState <- IO.fromOption(stateMachine.next(original, enriched), InvalidState(original, enriched))
          result   <- persist(enriched, original, newState, init)
          _        <- result.fold(
                        _ =>
                          Task.delay(
                            logger.error(
                              s"A unique violation has been raised for ${event.id}:${event.rev} of type $entityType"
                            )
                          ),
                        _ => Task.delay(logger.debug(s"Event and state for {}:{}of type {}", event.id, event.rev, entityType))
                      )
          _        <- init.updateCache(xas.cache)
        } yield (enriched, newState)
      }

      override def apply(migrate: ToMigrateEvent): Task[Unit] =
        for {
          _     <- Task.delay(logger.debug(s"[{} MigrationLog (Scoped)]", entityType))
          event <- Task.fromEither(definition.eventSerializer.codec.decodeJson(enrichJson(migrate.payload)))
          _     <- Task.delay(logger.debug(s"[{} MigrationLog (Scoped)] Will try to append the scoped event", entityType))
          _     <- Task.delay(
                     logger.debug(s"[{} MigrationLog (Scoped)] Event info: {} {}", entityType, event.project, event.id)
                   )
          _     <- append(event)
          _     <- Task.delay(logger.debug(s"[{} MigrationLog (Scoped)] Appended the scoped event", entityType))
        } yield ()
    }
  }

}
