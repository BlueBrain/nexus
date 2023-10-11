package ch.epfl.bluebrain.nexus.delta.sdk.permissions

import cats.effect.{Clock, IO}
import ch.epfl.bluebrain.nexus.delta.kernel.effect.migration.MigrateEffectSyntax
import ch.epfl.bluebrain.nexus.delta.kernel.kamon.KamonMetricComponent
import ch.epfl.bluebrain.nexus.delta.sdk.PermissionsResource
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.Permissions.{entityType, labelId}
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.PermissionsImpl.PermissionsLog
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.model.PermissionsCommand._
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.model.PermissionsRejection.{RevisionNotFound, UnexpectedState}
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.model._
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sourcing._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label

final class PermissionsImpl private (
    override val minimum: Set[Permission],
    log: PermissionsLog
) extends Permissions with MigrateEffectSyntax {

  implicit private val kamonComponent: KamonMetricComponent = KamonMetricComponent(entityType.value)

  private val initial = PermissionsState.initial(minimum)

  override def fetch: IO[PermissionsResource] =
    log
      .stateOr[PermissionsRejection](labelId, UnexpectedState).toCatsIO
      .handleErrorWith(_ => IO.pure(initial))
      .map(_.toResource(minimum))
      .span("fetchPermissions")

  override def fetchAt(rev: Int): IO[PermissionsResource] =
    log
      .stateOr(
        labelId,
        rev,
        UnexpectedState,
        RevisionNotFound
      ).toCatsIO
      .map(_.toResource(minimum))
      .span("fetchPermissionsAt")

  override def replace(
      permissions: Set[Permission],
      rev: Int
  )(implicit caller: Subject): IO[PermissionsResource] =
    eval(ReplacePermissions(rev, permissions, caller)).span("replacePermissions")

  override def append(
      permissions: Set[Permission],
      rev: Int
  )(implicit caller: Subject): IO[PermissionsResource] =
    eval(AppendPermissions(rev, permissions, caller)).span("appendPermissions")

  override def subtract(
      permissions: Set[Permission],
      rev: Int
  )(implicit caller: Subject): IO[PermissionsResource] =
    eval(SubtractPermissions(rev, permissions, caller)).span("subtractPermissions")

  override def delete(rev: Int)(implicit caller: Subject): IO[PermissionsResource] =
    eval(DeletePermissions(rev, caller)).span("deletePermissions")

  private def eval(cmd: PermissionsCommand): IO[PermissionsResource] =
    log.evaluate(labelId, cmd).toCatsIO
      .map {
        case (_, state) => state.toResource(minimum)
      }
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
  )(implicit clock: Clock[IO]): Permissions =
    new PermissionsImpl(config.minimum, GlobalEventLog(Permissions.definition(config.minimum), config.eventLog, xas))
}
