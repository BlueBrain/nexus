package ch.epfl.bluebrain.nexus.delta.sdk

import java.time.Instant
import java.util.concurrent.TimeUnit

import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.sdk.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.PermissionsCommand._
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.PermissionsEvent._
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.PermissionsRejection._
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.PermissionsState.{Current, Initial}
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions._
import monix.bio.{IO, UIO}

/**
  * Operations pertaining to managing permissions.
  */
trait Permissions {

  /**
    * @return the permissions singleton persistence id
    */
  def persistenceId: String

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
    fetch.map(_.value)

  /**
    * Replaces the current collection of permissions with the provided collection.
    *
    * @param permissions the permissions to set
    * @param rev         the last known revision of the resource
    * @return the new resource or a description of why the change was rejected
    */
  def replace(permissions: Set[Permission], rev: Long): IO[PermissionsRejection, PermissionsResource]

  /**
    * Appends the provided permissions to the current collection of permissions.
    *
    * @param permissions the permissions to append
    * @param rev         the last known revision of the resource
    * @return the new resource or a description of why the change was rejected
    */
  def append(permissions: Set[Permission], rev: Long): IO[PermissionsRejection, PermissionsResource]

  /**
    * Subtracts the provided permissions to the current collection of permissions.
    *
    * @param permissions the permissions to subtract
    * @param rev         the last known revision of the resource
    * @return the new resource or a description of why the change was rejected
    */
  def subtract(permissions: Set[Permission], rev: Long): IO[PermissionsRejection, PermissionsResource]

  /**
    * Removes all but the minimum permissions from the collection of permissions.
    *
    * @param rev the last known revision of the resource
    * @return the new resource or a description of why the change was rejected
    */
  def delete(rev: Long): IO[PermissionsRejection, PermissionsResource]
}

object Permissions {

  private def withPermissions(
      state: PermissionsState,
      minimum: Set[Permission]
  )(permissions: Set[Permission], instant: Instant, subject: Subject): PermissionsState = {
    state match {
      case Initial          =>
        Current(
          rev = 1L,
          permissions = permissions ++ minimum,
          createdAt = instant,
          createdBy = subject,
          updatedAt = instant,
          updatedBy = subject
        )
      case current: Current =>
        current.copy(
          rev = state.rev + 1,
          permissions = permissions ++ minimum,
          updatedAt = instant,
          updatedBy = subject
        )
    }
  }

  private[delta] def next(
      minimum: Set[Permission]
  )(state: PermissionsState, event: PermissionsEvent): PermissionsState = {
    val apply                                                  = withPermissions(state, minimum) _
    def appended(e: PermissionsAppended): PermissionsState     =
      state match {
        case Initial if e.rev == 1L           => apply(e.permissions, e.instant, e.subject)
        case s: Current if s.rev + 1 == e.rev => apply(s.permissions ++ e.permissions, e.instant, e.subject)
        case other                            => other
      }
    def replaced(e: PermissionsReplaced): PermissionsState     =
      state match {
        case s if s.rev + 1 == e.rev => apply(e.permissions, e.instant, e.subject)
        case other                   => other
      }
    def subtracted(e: PermissionsSubtracted): PermissionsState =
      state match {
        case s: Current if s.rev + 1 == e.rev => apply(s.permissions -- e.permissions, e.instant, e.subject)
        case other                            => other
      }
    def deleted(e: PermissionsDeleted): PermissionsState       =
      state match {
        case s: Current if s.rev + 1 == e.rev => apply(Set.empty, e.instant, e.subject)
        case other                            => other
      }
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
    def accept(f: Instant => PermissionsEvent): UIO[PermissionsEvent]            =
      clock.realTime(TimeUnit.MILLISECONDS).map(rt => f(Instant.ofEpochMilli(rt)))
    def reject[A <: PermissionsRejection](rejection: A): IO[A, PermissionsEvent] =
      IO.raiseError(rejection)

    def replace(c: ReplacePermissions): IO[PermissionsRejection, PermissionsEvent]   =
      if (c.rev != state.rev) reject(IncorrectRev(c.rev, state.rev))
      else if (c.permissions.isEmpty) reject(CannotReplaceWithEmptyCollection)
      else if ((c.permissions -- minimum).isEmpty) reject(CannotReplaceWithEmptyCollection)
      else accept(PermissionsReplaced(c.rev + 1, c.permissions, _, c.subject))
    def append(c: AppendPermissions): IO[PermissionsRejection, PermissionsEvent]     =
      state match {
        case _ if state.rev != c.rev    => reject(IncorrectRev(c.rev, state.rev))
        case _ if c.permissions.isEmpty => reject(CannotAppendEmptyCollection)
        case Initial                    =>
          val appended = c.permissions -- minimum
          if (appended.isEmpty) reject(CannotAppendEmptyCollection)
          else accept(PermissionsAppended(1L, c.permissions, _, c.subject))
        case s: Current                 =>
          val appended = c.permissions -- s.permissions -- minimum
          if (appended.isEmpty) reject(CannotAppendEmptyCollection)
          else accept(PermissionsAppended(c.rev + 1, c.permissions, _, c.subject))
      }
    def subtract(c: SubtractPermissions): IO[PermissionsRejection, PermissionsEvent] =
      state match {
        case _ if state.rev != c.rev    => reject(IncorrectRev(c.rev, state.rev))
        case _ if c.permissions.isEmpty => reject(CannotSubtractEmptyCollection)
        case Initial                    => reject(CannotSubtractFromMinimumCollection(minimum))
        case s: Current                 =>
          val intendedDelta = c.permissions -- s.permissions
          val delta         = c.permissions & s.permissions
          val subtracted    = delta -- minimum
          if (intendedDelta.nonEmpty) reject(CannotSubtractUndefinedPermissions(intendedDelta))
          else if (subtracted.isEmpty) reject(CannotSubtractFromMinimumCollection(minimum))
          else accept(PermissionsSubtracted(c.rev + 1, subtracted, _, c.subject))
      }
    def delete(c: DeletePermissions): IO[PermissionsRejection, PermissionsEvent]     =
      state match {
        case _ if state.rev != c.rev                => reject(IncorrectRev(c.rev, state.rev))
        case Initial                                => reject(CannotDeleteMinimumCollection)
        case s: Current if s.permissions == minimum => reject(CannotDeleteMinimumCollection)
        case _: Current                             => accept(PermissionsDeleted(c.rev + 1, _, c.subject))
      }

    cmd match {
      case c: ReplacePermissions  => replace(c)
      case c: AppendPermissions   => append(c)
      case c: SubtractPermissions => subtract(c)
      case c: DeletePermissions   => delete(c)
    }
  }
}
