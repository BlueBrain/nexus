package ch.epfl.bluebrain.nexus.delta.sdk.permissions

import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.kernel.database.Transactors
import ch.epfl.bluebrain.nexus.delta.kernel.kamon.KamonMetricComponent
import ch.epfl.bluebrain.nexus.delta.sdk.PermissionsResource
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.Permissions.{entityId, entityType}
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.PermissionsImpl.PermissionsLog
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.model.PermissionsCommand._
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.model.PermissionsRejection.{RevisionNotFound, UnexpectedState}
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.model._
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sourcing._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import monix.bio.{IO, UIO}

final class PermissionsImpl private (
    override val minimum: Set[Permission],
    log: PermissionsLog
) extends Permissions {

  implicit private val kamonComponent: KamonMetricComponent = KamonMetricComponent(entityType.value)

  private val initial = PermissionsState.initial(minimum)

  override def fetch: UIO[PermissionsResource] =
    log
      .stateOr(entityId, UnexpectedState)
      .onErrorHandle(_ => initial)
      .map(_.toResource(minimum))
      .span("fetchPermissions")

  override def fetchAt(rev: Int): IO[PermissionsRejection, PermissionsResource] =
    log
      .stateOr(
        entityId,
        rev,
        UnexpectedState,
        RevisionNotFound
      )
      .map(_.toResource(minimum))
      .span("fetchPermissionsAt")

  override def replace(
      permissions: Set[Permission],
      rev: Int
  )(implicit caller: Subject): IO[PermissionsRejection, PermissionsResource] =
    eval(ReplacePermissions(rev, permissions, caller)).span("replacePermissions")

  override def append(
      permissions: Set[Permission],
      rev: Int
  )(implicit caller: Subject): IO[PermissionsRejection, PermissionsResource] =
    eval(AppendPermissions(rev, permissions, caller)).span("appendPermissions")

  override def subtract(
      permissions: Set[Permission],
      rev: Int
  )(implicit caller: Subject): IO[PermissionsRejection, PermissionsResource] =
    eval(SubtractPermissions(rev, permissions, caller)).span("subtractPermissions")

  override def delete(rev: Int)(implicit caller: Subject): IO[PermissionsRejection, PermissionsResource] =
    eval(DeletePermissions(rev, caller)).span("deletePermissions")

  private def eval(cmd: PermissionsCommand): IO[PermissionsRejection, PermissionsResource] =
    log.evaluate(entityId, cmd).map(_._2.toResource(minimum))
}

object PermissionsImpl {

  type PermissionsLog =
    GlobalEventLog[Label, PermissionsState, PermissionsCommand, PermissionsEvent, PermissionsRejection]

  /**
    * Constructs a new [[Permissions]] instance
    * @param config
    *   the permissions module configuration
    * @param xas
    *   the doobie transactors
    */
  final def apply(
      config: PermissionsConfig,
      xas: Transactors
  )(implicit clock: Clock[UIO]): Permissions =
    new PermissionsImpl(config.minimum, GlobalEventLog(Permissions.definition(config.minimum), config.eventLog, xas))
}
