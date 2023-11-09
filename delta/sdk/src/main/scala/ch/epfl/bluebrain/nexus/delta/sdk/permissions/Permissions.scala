package ch.epfl.bluebrain.nexus.delta.sdk.permissions

import cats.effect.{Clock, IO}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.PermissionsResource
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceUris
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.model.PermissionsCommand._
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.model.PermissionsEvent._
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.model.PermissionsRejection._
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.model._
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{EntityType, Label}
import ch.epfl.bluebrain.nexus.delta.sourcing.{GlobalEntityDefinition, StateMachine}
import ch.epfl.bluebrain.nexus.delta.kernel.utils.IOInstant.now

import java.time.Instant

/**
  * Operations pertaining to managing permissions.
  */
trait Permissions {

  /**
    * @return
    *   the minimum set of permissions
    */
  def minimum: Set[Permission]

  /**
    * @return
    *   the current permissions as a resource
    */
  def fetch: IO[PermissionsResource]

  /**
    * @param rev
    *   the permissions revision
    * @return
    *   the permissions as a resource at the specified revision
    */
  def fetchAt(rev: Int): IO[PermissionsResource]

  /**
    * @return
    *   the current permissions collection without checking permissions
    */
  def fetchPermissionSet: IO[Set[Permission]] =
    fetch.map(_.value.permissions)

  /**
    * Replaces the current collection of permissions with the provided collection.
    *
    * @param permissions
    *   the permissions to set
    * @param rev
    *   the last known revision of the resource
    * @param caller
    *   a reference to the subject that initiated the action
    * @return
    *   the new resource or a description of why the change was rejected
    */
  def replace(
      permissions: Set[Permission],
      rev: Int
  )(implicit caller: Subject): IO[PermissionsResource]

  /**
    * Appends the provided permissions to the current collection of permissions.
    *
    * @param permissions
    *   the permissions to append
    * @param rev
    *   the last known revision of the resource
    * @param caller
    *   a reference to the subject that initiated the action
    * @return
    *   the new resource or a description of why the change was rejected
    */
  def append(
      permissions: Set[Permission],
      rev: Int
  )(implicit caller: Subject): IO[PermissionsResource]

  /**
    * Subtracts the provided permissions to the current collection of permissions.
    *
    * @param permissions
    *   the permissions to subtract
    * @param rev
    *   the last known revision of the resource
    * @param caller
    *   a reference to the subject that initiated the action
    * @return
    *   the new resource or a description of why the change was rejected
    */
  def subtract(
      permissions: Set[Permission],
      rev: Int
  )(implicit caller: Subject): IO[PermissionsResource]

  /**
    * Removes all but the minimum permissions from the collection of permissions.
    *
    * @param rev
    *   the last known revision of the resource
    * @param caller
    *   a reference to the subject that initiated the action
    * @return
    *   the new resource or a description of why the change was rejected
    */
  def delete(rev: Int)(implicit caller: Subject): IO[PermissionsResource]
}

object Permissions {

  final val entityType: EntityType = EntityType("permissions")

  /**
    * Id of the singleton permissions entity
    */
  val id: Iri = ResourceUris.permissions.relativeAccessUri.toIri

  /**
    * The constant entity id.
    */
  val labelId: Label = Label.unsafe("permissions")

  /**
    * ACLs permissions.
    */
  object acls {
    final val read: Permission  = Permission.unsafe("acls/read")
    final val write: Permission = Permission.unsafe("acls/write")
  }

  /**
    * Realms permissions.
    */
  object realms {
    final val read: Permission  = Permission.unsafe("realms/read")
    final val write: Permission = Permission.unsafe("realms/write")
  }

  /**
    * Permissions permissions.
    */
  object permissions {
    final val read: Permission  = Permission.unsafe("permissions/read")
    final val write: Permission = Permission.unsafe("permissions/write")
  }

  /**
    * Organizations permissions.
    */
  object orgs {
    final val read: Permission   = Permission.unsafe("organizations/read")
    final val write: Permission  = Permission.unsafe("organizations/write")
    final val create: Permission = Permission.unsafe("organizations/create")
    final val delete: Permission = Permission.unsafe("organizations/delete")
  }

  /**
    * Projects permissions.
    */
  object projects {
    final val read: Permission   = Permission.unsafe("projects/read")
    final val write: Permission  = Permission.unsafe("projects/write")
    final val delete: Permission = Permission.unsafe("projects/delete")
    final val create: Permission = Permission.unsafe("projects/create")
  }

  /**
    * Generic event permissions.
    */
  object events {
    final val read: Permission = Permission.unsafe("events/read")
  }

  /**
    * Generic version permissions.
    */
  object version {
    final val read: Permission = Permission.unsafe("version/read")
  }

  /**
    * Resources permissions.
    */
  object resources {
    final val read: Permission  = Permission.unsafe("resources/read")
    final val write: Permission = Permission.unsafe("resources/write")
  }

  /**
    * Schemas permissions.
    */
  object schemas {
    final val read: Permission  = resources.read
    final val write: Permission = Permission.unsafe("schemas/write")
  }

  /**
    * Resolvers permissions.
    */
  object resolvers {
    final val read: Permission  = resources.read
    final val write: Permission = Permission.unsafe("resolvers/write")
  }

  /**
    * Quotas permissions.
    */
  object quotas {
    final val read: Permission = Permission.unsafe("quotas/read")
  }

  object supervision {
    final val read: Permission = Permission.unsafe("supervision/read")
  }

  private[delta] def next(
      minimum: Set[Permission]
  )(state: PermissionsState, event: PermissionsEvent): PermissionsState = {

    implicit class WithPermissionsState(s: PermissionsState) {
      def withPermissions(permissions: Set[Permission], instant: Instant, subject: Subject): PermissionsState =
        s match {
          case c if c.rev == 0 => PermissionsState(1, permissions, instant, subject, instant, subject)
          case c               => PermissionsState(c.rev + 1, permissions, c.createdAt, c.createdBy, instant, subject)
        }
    }

    def appended(e: PermissionsAppended) =
      state.withPermissions(state.permissions ++ e.permissions ++ minimum, e.instant, e.subject)

    def replaced(e: PermissionsReplaced) =
      state.withPermissions(minimum ++ e.permissions, e.instant, e.subject)

    def subtracted(e: PermissionsSubtracted) =
      state.withPermissions(state.permissions -- e.permissions ++ minimum, e.instant, e.subject)

    def deleted(e: PermissionsDeleted) =
      state.withPermissions(minimum, e.instant, e.subject)

    event match {
      case e: PermissionsAppended   => appended(e)
      case e: PermissionsReplaced   => replaced(e)
      case e: PermissionsSubtracted => subtracted(e)
      case e: PermissionsDeleted    => deleted(e)
    }
  }

  private[delta] def evaluate(minimum: Set[Permission])(state: PermissionsState, cmd: PermissionsCommand)(implicit
      clock: Clock[IO]
  ): IO[PermissionsEvent] = {
    def replace(c: ReplacePermissions) =
      if (c.rev != state.rev) IO.raiseError(IncorrectRev(c.rev, state.rev))
      else if (c.permissions.isEmpty) IO.raiseError(CannotReplaceWithEmptyCollection)
      else if ((c.permissions -- minimum).isEmpty) IO.raiseError(CannotReplaceWithEmptyCollection)
      else now.map(PermissionsReplaced(c.rev + 1, c.permissions, _, c.subject))

    def append(c: AppendPermissions) =
      state match {
        case _ if state.rev != c.rev    => IO.raiseError(IncorrectRev(c.rev, state.rev))
        case _ if c.permissions.isEmpty => IO.raiseError(CannotAppendEmptyCollection)
        case s                          =>
          val appended = c.permissions -- s.permissions -- minimum
          if (appended.isEmpty) IO.raiseError(CannotAppendEmptyCollection)
          else now.map(PermissionsAppended(c.rev + 1, appended, _, c.subject))
      }

    def subtract(c: SubtractPermissions) =
      state match {
        case _ if state.rev != c.rev    => IO.raiseError(IncorrectRev(c.rev, state.rev))
        case _ if c.permissions.isEmpty => IO.raiseError(CannotSubtractEmptyCollection)
        case s if s.rev == 0            => IO.raiseError(CannotSubtractFromMinimumCollection(minimum))
        case s                          =>
          val intendedDelta = c.permissions -- s.permissions
          val delta         = c.permissions & s.permissions
          val subtracted    = delta -- minimum
          if (intendedDelta.nonEmpty) IO.raiseError(CannotSubtractUndefinedPermissions(intendedDelta))
          else if (subtracted.isEmpty) IO.raiseError(CannotSubtractFromMinimumCollection(minimum))
          else now.map(PermissionsSubtracted(c.rev + 1, subtracted, _, c.subject))
      }

    def delete(c: DeletePermissions) =
      state match {
        case _ if state.rev != c.rev       => IO.raiseError(IncorrectRev(c.rev, state.rev))
        case s if s.permissions == minimum => IO.raiseError(CannotDeleteMinimumCollection)
        case _                             => now.map(PermissionsDeleted(c.rev + 1, _, c.subject))
      }

    cmd match {
      case c: ReplacePermissions  => replace(c)
      case c: AppendPermissions   => append(c)
      case c: SubtractPermissions => subtract(c)
      case c: DeletePermissions   => delete(c)
    }
  }

  /**
    * Entity definition for [[Permissions]]
    *
    * @param minimum
    *   the minimum set of permissions
    */
  def definition(minimum: Set[Permission])(implicit
      clock: Clock[IO]
  ): GlobalEntityDefinition[Label, PermissionsState, PermissionsCommand, PermissionsEvent, PermissionsRejection] = {
    val initial = PermissionsState.initial(minimum)
    GlobalEntityDefinition(
      entityType,
      StateMachine(
        Some(initial),
        (state: Option[PermissionsState], cmd: PermissionsCommand) => evaluate(minimum)(state.getOrElse(initial), cmd),
        (state: Option[PermissionsState], event: PermissionsEvent) =>
          Some(next(minimum)(state.getOrElse(initial), event))
      ),
      PermissionsEvent.serializer,
      PermissionsState.serializer,
      onUniqueViolation = (_: Label, c: PermissionsCommand) => IncorrectRev(c.rev, c.rev + 1)
    )
  }
}
