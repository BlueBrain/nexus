package ch.epfl.bluebrain.nexus.delta.sdk

import java.time.Instant

import akka.persistence.query.{NoOffset, Offset}
import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.sdk.model.Envelope
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.PermissionsCommand._
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.PermissionsEvent._
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.PermissionsRejection._
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.PermissionsState.{Current, Initial}
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions._
import fs2.Stream
import monix.bio.{IO, Task, UIO}

/**
  * Operations pertaining to managing permissions.
  */
trait Permissions {

  /**
    * @return the permissions singleton persistence id
    */
  def persistenceId: String = Permissions.persistenceId

  /**
    * @return the minimum set of permissions
    */
  def minimum: Set[Permission]

  /**
    * @return the current permissions as a resource
    */
  def fetch: UIO[PermissionsResource]

  /**
    * @param rev the permissions revision
    * @return the permissions as a resource at the specified revision
    */
  def fetchAt(rev: Long): IO[RevisionNotFound, PermissionsResource]

  /**
    * @return the current permissions collection without checking permissions
    */
  def fetchPermissionSet: UIO[Set[Permission]] =
    fetch.map(_.value.permissions)

  /**
    * Replaces the current collection of permissions with the provided collection.
    *
    * @param permissions the permissions to set
    * @param rev         the last known revision of the resource
    * @param caller      a reference to the subject that initiated the action
    * @return the new resource or a description of why the change was rejected
    */
  def replace(
      permissions: Set[Permission],
      rev: Long
  )(implicit caller: Subject): IO[PermissionsRejection, PermissionsResource]

  /**
    * Appends the provided permissions to the current collection of permissions.
    *
    * @param permissions the permissions to append
    * @param rev         the last known revision of the resource
    * @param caller      a reference to the subject that initiated the action
    * @return the new resource or a description of why the change was rejected
    */
  def append(
      permissions: Set[Permission],
      rev: Long
  )(implicit caller: Subject): IO[PermissionsRejection, PermissionsResource]

  /**
    * Subtracts the provided permissions to the current collection of permissions.
    *
    * @param permissions the permissions to subtract
    * @param rev         the last known revision of the resource
    * @param caller      a reference to the subject that initiated the action
    * @return the new resource or a description of why the change was rejected
    */
  def subtract(
      permissions: Set[Permission],
      rev: Long
  )(implicit caller: Subject): IO[PermissionsRejection, PermissionsResource]

  /**
    * Removes all but the minimum permissions from the collection of permissions.
    *
    * @param rev    the last known revision of the resource
    * @param caller a reference to the subject that initiated the action
    * @return the new resource or a description of why the change was rejected
    */
  def delete(rev: Long)(implicit caller: Subject): IO[PermissionsRejection, PermissionsResource]

  /**
    * A non terminating stream of events for permissions. After emitting all known events it sleeps until new events
    * are recorded.
    *
    * @param offset the last seen event offset; it will not be emitted by the stream
    */
  def events(offset: Offset = NoOffset): Stream[Task, Envelope[PermissionsEvent]]

  /**
    * The current permissions events. The stream stops after emitting all known events.
    *
    * @param offset the last seen event offset; it will not be emitted by the stream
    */
  def currentEvents(offset: Offset = NoOffset): Stream[Task, Envelope[PermissionsEvent]]
}

object Permissions {

  /**
    * The permissions module type.
    */
  val moduleType: String = "permissions"

  /**
    * The constant entity id.
    */
  val entityId: String = "permissions"

  val persistenceId: String = s"$moduleType-$entityId"

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
  }

  /**
    * Projects permissions.
    */
  object projects {
    final val read: Permission   = Permission.unsafe("projects/read")
    final val write: Permission  = Permission.unsafe("projects/write")
    final val create: Permission = Permission.unsafe("projects/create")
  }

  /**
    * Generic event permissions.
    */
  object events {
    final val read: Permission = Permission.unsafe("events/read")
  }

  /**
    * Generic plugins permissions.
    */
  object plugins {
    final val read: Permission = Permission.unsafe("plugins/read")
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

  private[delta] def next(
      minimum: Set[Permission]
  )(state: PermissionsState, event: PermissionsEvent): PermissionsState = {

    implicit class WithPermissionsState(s: PermissionsState) {
      def withPermissions(permissions: Set[Permission], instant: Instant, subject: Subject): PermissionsState =
        s match {
          case Initial    => Current(s.rev + 1L, permissions, instant, subject, instant, subject)
          case c: Current => Current(c.rev + 1L, permissions, c.createdAt, c.createdBy, instant, subject)
        }

      def permissions: Set[Permission] =
        s match {
          case Initial    => minimum
          case c: Current => c.permissions
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
      clock: Clock[UIO] = IO.timer.clock
  ): IO[PermissionsRejection, PermissionsEvent] = {
    import ch.epfl.bluebrain.nexus.delta.sdk.utils.IOUtils._

    def replace(c: ReplacePermissions) =
      if (c.rev != state.rev) IO.raiseError(IncorrectRev(c.rev, state.rev))
      else if (c.permissions.isEmpty) IO.raiseError(CannotReplaceWithEmptyCollection)
      else if ((c.permissions -- minimum).isEmpty) IO.raiseError(CannotReplaceWithEmptyCollection)
      else instant.map(PermissionsReplaced(c.rev + 1, c.permissions, _, c.subject))

    def append(c: AppendPermissions) =
      state match {
        case _ if state.rev != c.rev    => IO.raiseError(IncorrectRev(c.rev, state.rev))
        case _ if c.permissions.isEmpty => IO.raiseError(CannotAppendEmptyCollection)
        case Initial                    =>
          val appended = c.permissions -- minimum
          if (appended.isEmpty) IO.raiseError(CannotAppendEmptyCollection)
          else instant.map(PermissionsAppended(1L, c.permissions, _, c.subject))
        case s: Current                 =>
          val appended = c.permissions -- s.permissions -- minimum
          if (appended.isEmpty) IO.raiseError(CannotAppendEmptyCollection)
          else instant.map(PermissionsAppended(c.rev + 1, appended, _, c.subject))
      }

    def subtract(c: SubtractPermissions) =
      state match {
        case _ if state.rev != c.rev    => IO.raiseError(IncorrectRev(c.rev, state.rev))
        case _ if c.permissions.isEmpty => IO.raiseError(CannotSubtractEmptyCollection)
        case Initial                    => IO.raiseError(CannotSubtractFromMinimumCollection(minimum))
        case s: Current                 =>
          val intendedDelta = c.permissions -- s.permissions
          val delta         = c.permissions & s.permissions
          val subtracted    = delta -- minimum
          if (intendedDelta.nonEmpty) IO.raiseError(CannotSubtractUndefinedPermissions(intendedDelta))
          else if (subtracted.isEmpty) IO.raiseError(CannotSubtractFromMinimumCollection(minimum))
          else instant.map(PermissionsSubtracted(c.rev + 1, subtracted, _, c.subject))
      }

    def delete(c: DeletePermissions) =
      state match {
        case _ if state.rev != c.rev                => IO.raiseError(IncorrectRev(c.rev, state.rev))
        case Initial                                => IO.raiseError(CannotDeleteMinimumCollection)
        case s: Current if s.permissions == minimum => IO.raiseError(CannotDeleteMinimumCollection)
        case _: Current                             => instant.map(PermissionsDeleted(c.rev + 1, _, c.subject))
      }

    cmd match {
      case c: ReplacePermissions  => replace(c)
      case c: AppendPermissions   => append(c)
      case c: SubtractPermissions => subtract(c)
      case c: DeletePermissions   => delete(c)
    }
  }
}
