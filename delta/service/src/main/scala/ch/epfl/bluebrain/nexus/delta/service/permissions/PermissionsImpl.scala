package ch.epfl.bluebrain.nexus.delta.service.permissions

import akka.actor.typed.ActorSystem
import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.PermissionsCommand._
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.PermissionsRejection.RevisionNotFound
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions._
import ch.epfl.bluebrain.nexus.delta.sdk.{Permissions, PermissionsResource}
import ch.epfl.bluebrain.nexus.delta.service.permissions.PermissionsImpl.{entityId, entityType, PermissionsAggregate}
import ch.epfl.bluebrain.nexus.sourcing._
import ch.epfl.bluebrain.nexus.sourcing.processor.{AggregateConfig, ShardedAggregate, StopStrategy}
import monix.bio.{IO, UIO}

final class PermissionsImpl private (
    override val minimum: Set[Permission],
    agg: PermissionsAggregate,
    base: Uri
) extends Permissions {

  private val id: Iri = iri"$base/permissions"

  override val persistenceId: String = s"$entityType-$entityId"

  override def fetch: UIO[PermissionsResource] = {
    agg.state(entityId).map(_.toResource(id, minimum)).hideErrors
  }

  override def fetchAt(rev: Long): IO[PermissionsRejection.RevisionNotFound, PermissionsResource] =
    if (rev == 0L) UIO.pure(PermissionsState.Initial.toResource(id, minimum))
    else
      agg
        .events(entityId)
        .takeWhile(_.rev <= rev)
        .fold[PermissionsState](PermissionsState.Initial) {
          case (state, event) => Permissions.next(minimum)(state, event)
        }
        .compile
        .last
        .hideErrors
        .flatMap {
          case Some(state) if state.rev == rev => UIO.pure(state.toResource(id, minimum))
          case Some(_)                         => fetch.flatMap(res => IO.raiseError(RevisionNotFound(rev, res.rev)))
          case None                            => IO.raiseError(RevisionNotFound(rev, 0L))
        }

  override def replace(
      permissions: Set[Permission],
      rev: Long
  )(implicit caller: Subject): IO[PermissionsRejection, PermissionsResource] =
    eval(ReplacePermissions(rev, permissions, caller))

  override def append(
      permissions: Set[Permission],
      rev: Long
  )(implicit caller: Subject): IO[PermissionsRejection, PermissionsResource] =
    eval(AppendPermissions(rev, permissions, caller))

  override def subtract(
      permissions: Set[Permission],
      rev: Long
  )(implicit caller: Subject): IO[PermissionsRejection, PermissionsResource] =
    eval(SubtractPermissions(rev, permissions, caller))

  override def delete(rev: Long)(implicit caller: Subject): IO[PermissionsRejection, PermissionsResource] =
    eval(DeletePermissions(rev, caller))

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
    * Constructs a permissions aggregate. It requires that the system has joined a cluster. The implementation is
    * effectful as it allocates a new cluster sharding entity across the cluster.
    *
    * @param minimum         the minimum collection of permissions
    * @param aggregateConfig the aggregate configuration
    * @param eventLog        an event log instance for events of type [[PermissionsEvent]]
    */
  final def aggregate(
      minimum: Set[Permission],
      aggregateConfig: AggregateConfig,
      eventLog: EventLog[PermissionsEvent]
  )(implicit as: ActorSystem[Nothing]): UIO[PermissionsAggregate] = {
    val definition = PersistentEventDefinition(
      entityType = entityType,
      initialState = PermissionsState.Initial,
      next = Permissions.next(minimum),
      evaluate = Permissions.evaluate(minimum),
      tagger = (_: PermissionsEvent) => Set("permissions"),
      snapshotStrategy =
        SnapshotStrategy.SnapshotEvery(numberOfEvents = 500, keepNSnapshots = 1, deleteEventsOnSnapshot = false),
      stopStrategy = StopStrategy.PersistentStopStrategy.never
    )
    ShardedAggregate
      .persistentSharded(
        eventLog = eventLog,
        definition = definition,
        config = aggregateConfig,
        retryStrategy = RetryStrategy.alwaysGiveUp
        // TODO: configure the number of shards
      )
  }

  /**
    * Constructs a new [[Permissions]] instance backed by a sharded aggregate.
    *
    * @param minimum the minimum collection of permissions
    * @param agg     the permissions aggregate
    * @param base    the base uri of the system API
    */
  final def apply(minimum: Set[Permission], agg: PermissionsAggregate, base: Uri): Permissions =
    new PermissionsImpl(minimum, agg, base)

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
      base: Uri,
      aggregateConfig: AggregateConfig,
      eventLog: EventLog[PermissionsEvent]
  )(implicit as: ActorSystem[Nothing]): UIO[Permissions] =
    aggregate(minimum, aggregateConfig, eventLog).map { agg =>
      apply(minimum, agg, base)
    }
}
