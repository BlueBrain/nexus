package ch.epfl.bluebrain.nexus.delta.service.permissions

import akka.actor.typed.ActorSystem
import akka.persistence.query.{NoOffset, Offset}
import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.PermissionsCommand._
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.PermissionsRejection.RevisionNotFound
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions._
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Envelope}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.{Permissions, PermissionsResource}
import ch.epfl.bluebrain.nexus.delta.service.config.AggregateConfig
import ch.epfl.bluebrain.nexus.delta.service.permissions.PermissionsImpl.{entityId, entityType, permissionsTag, PermissionsAggregate}
import ch.epfl.bluebrain.nexus.sourcing._
import ch.epfl.bluebrain.nexus.sourcing.processor.ShardedAggregate
import fs2.Stream
import monix.bio.{IO, Task, UIO}

final class PermissionsImpl private (
    override val minimum: Set[Permission],
    agg: PermissionsAggregate,
    eventLog: EventLog[Envelope[PermissionsEvent]],
    base: BaseUri
) extends Permissions {

  private val id: Iri           = iri"${base.endpoint}/permissions"
  private val component: String = "permissions"

  override val persistenceId: String = s"$entityType-$entityId"

  override def fetch: UIO[PermissionsResource] =
    agg.state(entityId).map(_.toResource(id, minimum)).named("fetchPermissions", component)

  override def fetchAt(rev: Long): IO[PermissionsRejection.RevisionNotFound, PermissionsResource] =
    if (rev == 0L)
      UIO
        .pure(PermissionsState.Initial.toResource(id, minimum))
        .named("fetchPermissionsAt", component, Map("rev" -> rev))
    else {
      eventLog
        .currentEventsByPersistenceId(persistenceId, Long.MinValue, Long.MaxValue)
        .takeWhile(_.event.rev <= rev)
        .fold[PermissionsState](PermissionsState.Initial) { case (state, event) =>
          Permissions.next(minimum)(state, event.event)
        }
        .compile
        .last
        .hideErrors
        .flatMap {
          case Some(state) if state.rev == rev => UIO.pure(state.toResource(id, minimum))
          case Some(_)                         => fetch.flatMap(res => IO.raiseError(RevisionNotFound(rev, res.rev)))
          case None                            => IO.raiseError(RevisionNotFound(rev, 0L))
        }
        .named("fetchPermissionsAt", component, Map("rev" -> rev))
    }

  override def replace(
      permissions: Set[Permission],
      rev: Long
  )(implicit caller: Subject): IO[PermissionsRejection, PermissionsResource] =
    eval(ReplacePermissions(rev, permissions, caller)).named("replacePermissions", component)

  override def append(
      permissions: Set[Permission],
      rev: Long
  )(implicit caller: Subject): IO[PermissionsRejection, PermissionsResource] =
    eval(AppendPermissions(rev, permissions, caller)).named("appendPermissions", component)

  override def subtract(
      permissions: Set[Permission],
      rev: Long
  )(implicit caller: Subject): IO[PermissionsRejection, PermissionsResource] =
    eval(SubtractPermissions(rev, permissions, caller)).named("subtractPermissions", component)

  override def delete(rev: Long)(implicit caller: Subject): IO[PermissionsRejection, PermissionsResource] =
    eval(DeletePermissions(rev, caller)).named("deletePermissions", component)

  override def events(offset: Offset): Stream[Task, Envelope[PermissionsEvent]] =
    offset match {
      case NoOffset => eventLog.eventsByPersistenceId(persistenceId, Long.MinValue, Long.MaxValue)
      case _        => eventLog.eventsByTag(permissionsTag, offset)
    }

  override def currentEvents(offset: Offset): Stream[Task, Envelope[PermissionsEvent]] =
    offset match {
      case NoOffset => eventLog.currentEventsByPersistenceId(persistenceId, Long.MinValue, Long.MaxValue)
      case _        => eventLog.currentEventsByTag(permissionsTag, offset)
    }

  private def eval(cmd: PermissionsCommand): IO[PermissionsRejection, PermissionsResource] =
    agg.evaluate(entityId, cmd).mapError(_.value).map { success =>
      success.state.toResource(id, minimum)
    }
}

object PermissionsImpl {

  /**
    * Type alias to the permissions aggregate.
    */
  type PermissionsAggregate =
    Aggregate[String, PermissionsState, PermissionsCommand, PermissionsEvent, PermissionsRejection]

  /**
    * The permissions entity type.
    */
  final val entityType: String = "permissions"

  /**
    * The constant entity id.
    */
  final val entityId: String = "permissions"

  /**
    * The constant tag for permissions events.
    */
  final val permissionsTag = "permissions"

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
      entityType = entityType,
      initialState = PermissionsState.Initial,
      next = Permissions.next(minimum),
      evaluate = Permissions.evaluate(minimum),
      tagger = (_: PermissionsEvent) => Set(permissionsTag),
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
      eventLog: EventLog[Envelope[PermissionsEvent]],
      base: BaseUri
  ): Permissions =
    new PermissionsImpl(minimum, agg, eventLog, base)

  /**
    *  Constructs a new [[Permissions]] instance backed by a sharded aggregate. It requires that the system has joined
    *  a cluster. The implementation is effectful as it allocates a new cluster sharding entity across the cluster.
    *
    * @param minimum         the minimum collection of permissions
    * @param base            the base uri of the system API
    * @param aggregateConfig the aggregate configuration
    * @param eventLog        an event log instance for events of type [[PermissionsEvent]]
    */
  final def apply(
      minimum: Set[Permission],
      base: BaseUri,
      aggregateConfig: AggregateConfig,
      eventLog: EventLog[Envelope[PermissionsEvent]]
  )(implicit as: ActorSystem[Nothing], clock: Clock[UIO]): UIO[Permissions] =
    aggregate(minimum, aggregateConfig).map { agg =>
      apply(minimum, agg, eventLog, base)
    }
}
