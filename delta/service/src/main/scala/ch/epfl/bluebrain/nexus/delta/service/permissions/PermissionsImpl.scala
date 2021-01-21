package ch.epfl.bluebrain.nexus.delta.service.permissions

import akka.actor.typed.ActorSystem
import akka.persistence.query.{NoOffset, Offset}
import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategy
import ch.epfl.bluebrain.nexus.delta.sdk.Permissions.{entityId, moduleType}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.PermissionsCommand._
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.PermissionsRejection.RevisionNotFound
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.PermissionsState.Initial
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions._
import ch.epfl.bluebrain.nexus.delta.sdk.model.Envelope
import ch.epfl.bluebrain.nexus.delta.sdk.{Permissions, PermissionsResource}
import ch.epfl.bluebrain.nexus.delta.service.permissions.PermissionsImpl.PermissionsAggregate
import ch.epfl.bluebrain.nexus.delta.service.syntax._
import ch.epfl.bluebrain.nexus.sourcing._
import ch.epfl.bluebrain.nexus.sourcing.config.AggregateConfig
import ch.epfl.bluebrain.nexus.sourcing.processor.ShardedAggregate
import fs2.Stream
import monix.bio.{IO, Task, UIO}

final class PermissionsImpl private (
    override val minimum: Set[Permission],
    agg: PermissionsAggregate,
    eventLog: EventLog[Envelope[PermissionsEvent]]
) extends Permissions {

  override def fetch: UIO[PermissionsResource] =
    agg.state(entityId).map(_.toResource(minimum)).named("fetchPermissions", moduleType)

  override def fetchAt(rev: Long): IO[PermissionsRejection.RevisionNotFound, PermissionsResource] =
    eventLog
      .fetchStateAt(
        persistenceId,
        rev,
        Initial,
        Permissions.next(minimum)
      )
      .bimap(RevisionNotFound(rev, _), _.toResource(minimum))
      .named("fetchPermissionsAt", moduleType)

  override def replace(
      permissions: Set[Permission],
      rev: Long
  )(implicit caller: Subject): IO[PermissionsRejection, PermissionsResource] =
    eval(ReplacePermissions(rev, permissions, caller)).named("replacePermissions", moduleType)

  override def append(
      permissions: Set[Permission],
      rev: Long
  )(implicit caller: Subject): IO[PermissionsRejection, PermissionsResource] =
    eval(AppendPermissions(rev, permissions, caller)).named("appendPermissions", moduleType)

  override def subtract(
      permissions: Set[Permission],
      rev: Long
  )(implicit caller: Subject): IO[PermissionsRejection, PermissionsResource] =
    eval(SubtractPermissions(rev, permissions, caller)).named("subtractPermissions", moduleType)

  override def delete(rev: Long)(implicit caller: Subject): IO[PermissionsRejection, PermissionsResource] =
    eval(DeletePermissions(rev, caller)).named("deletePermissions", moduleType)

  override def events(offset: Offset): Stream[Task, Envelope[PermissionsEvent]] =
    offset match {
      case NoOffset => eventLog.eventsByPersistenceId(persistenceId, Long.MinValue, Long.MaxValue)
      case _        => eventLog.eventsByTag(moduleType, offset)
    }

  override def currentEvents(offset: Offset): Stream[Task, Envelope[PermissionsEvent]] =
    offset match {
      case NoOffset => eventLog.currentEventsByPersistenceId(persistenceId, Long.MinValue, Long.MaxValue)
      case _        => eventLog.currentEventsByTag(moduleType, offset)
    }

  private def eval(cmd: PermissionsCommand): IO[PermissionsRejection, PermissionsResource] =
    agg.evaluate(entityId, cmd).mapError(_.value).map { success =>
      success.state.toResource(minimum)
    }
}

object PermissionsImpl {

  /**
    * Type alias to the permissions aggregate.
    */
  type PermissionsAggregate =
    Aggregate[String, PermissionsState, PermissionsCommand, PermissionsEvent, PermissionsRejection]

  /**
    * Constructs a permissions aggregate. It requires that the system has joined a cluster. The implementation is
    * effectful as it allocates a new cluster sharding entity across the cluster.
    *
    * @param minimum         the minimum collection of permissions
    * @param aggregateConfig the aggregate configuration
    */
  final def aggregate(
      minimum: Set[Permission],
      aggregateConfig: AggregateConfig
  )(implicit as: ActorSystem[Nothing], clock: Clock[UIO]): UIO[PermissionsAggregate] = {
    val definition = PersistentEventDefinition(
      entityType = moduleType,
      initialState = PermissionsState.Initial,
      next = Permissions.next(minimum),
      evaluate = Permissions.evaluate(minimum),
      tagger = (_: PermissionsEvent) => Set(moduleType),
      snapshotStrategy = aggregateConfig.snapshotStrategy.strategy,
      stopStrategy = aggregateConfig.stopStrategy.persistentStrategy
    )
    ShardedAggregate
      .persistentSharded(
        definition = definition,
        config = aggregateConfig.processor,
        retryStrategy = RetryStrategy.alwaysGiveUp
        // TODO: configure the number of shards
      )
  }

  /**
    * Constructs a new [[Permissions]] instance backed by a sharded aggregate.
    *
    * @param minimum  the minimum collection of permissions
    * @param agg      the permissions aggregate
    * @param eventLog the permissions event log
    * @param base     the base uri of the system API
    */
  final def apply(
      minimum: Set[Permission],
      agg: PermissionsAggregate,
      eventLog: EventLog[Envelope[PermissionsEvent]]
  ): Permissions =
    new PermissionsImpl(minimum, agg, eventLog)

  /**
    * Constructs a new [[Permissions]] instance backed by a sharded aggregate. It requires that the system has joined
    * a cluster. The implementation is effectful as it allocates a new cluster sharding entity across the cluster.
    *
    * @param minimum         the minimum collection of permissions
    * @param aggregateConfig the aggregate configuration
    * @param eventLog        an event log instance for events of type [[PermissionsEvent]]
    * @param base            the base uri of the system API
    */
  final def apply(
      minimum: Set[Permission],
      aggregateConfig: AggregateConfig,
      eventLog: EventLog[Envelope[PermissionsEvent]]
  )(implicit as: ActorSystem[Nothing], clock: Clock[UIO]): UIO[Permissions] =
    aggregate(minimum, aggregateConfig).map { agg =>
      apply(minimum, agg, eventLog)
    }
}
