package ch.epfl.bluebrain.nexus.delta.service.permissions

import akka.actor.typed.ActorSystem
import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.delta.rdf.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.PermissionsCommand._
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.PermissionsRejection.RevisionNotFound
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions._
import ch.epfl.bluebrain.nexus.delta.sdk.{Permissions, PermissionsResource}
import ch.epfl.bluebrain.nexus.delta.service.permissions.PermissionsImpl.PermissionsAggregate
import ch.epfl.bluebrain.nexus.sourcing.processor.{AggregateConfig, ShardedAggregate, StopStrategy}
import ch.epfl.bluebrain.nexus.sourcing._
import monix.bio.{IO, UIO}
import org.apache.jena.iri.IRI

final class PermissionsImpl private(
    override val minimum: Set[Permission],
    agg: PermissionsAggregate,
    base: Uri
) extends Permissions {
  val id: IRI = iri"$base/permissions"

  override val persistenceId: String = PermissionsImpl.persistenceId

  override def fetch: UIO[PermissionsResource] = {
    agg.state(persistenceId).map(_.toResource(id, minimum)).hideErrors
  }

  override def fetchAt(rev: Long): IO[PermissionsRejection.RevisionNotFound, PermissionsResource] =
    if (rev == 0L) UIO.pure(PermissionsState.Initial.toResource(id, minimum))
    else
      agg
        .events(persistenceId)
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
    agg.evaluate(persistenceId, cmd).mapError(_.value).map { success =>
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
    * The constant persistenceId.
    */
  final val persistenceId: String = "permissions"

  final def aggregate(
      minimum: Set[Permission],
      permissionsCfg: AggregateConfig,
      eventLog: EventLog[PermissionsEvent]
  )(implicit as: ActorSystem[Nothing]): UIO[PermissionsAggregate] = {
    val definition = PersistentEventDefinition(
      entityType = "permissions",
      initialState = PermissionsState.Initial,
      next = Permissions.next(minimum),
      evaluate = Permissions.evaluate(minimum),
      tagger = (_: PermissionsEvent) => Set("permissions"),
      snapshotStrategy =
        SnapshotStrategy.SnapshotEvery(numberOfEvents = 1000, keepNSnapshots = 1, deleteEventsOnSnapshot = false),
      stopStrategy = StopStrategy.PersistentStopStrategy.never
    )
    ShardedAggregate
      .persistentSharded(
        eventLog = eventLog,
        definition = definition,
        config = permissionsCfg,
        retryStrategy = RetryStrategy.alwaysGiveUp
        // TODO: configure the number of shards
      )
  }

  final def apply(minimum: Set[Permission], agg: PermissionsAggregate, base: Uri): Permissions =
    new PermissionsImpl(minimum, agg, base)

  final def apply(
      minimum: Set[Permission],
      base: Uri,
      permissionsCfg: AggregateConfig,
      eventLog: EventLog[PermissionsEvent]
  )(implicit as: ActorSystem[Nothing]): UIO[Permissions] =
    aggregate(minimum, permissionsCfg, eventLog).map { agg =>
      apply(minimum, agg, base)
    }
}
