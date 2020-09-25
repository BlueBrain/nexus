package ch.epfl.bluebrain.nexus.delta.sdk

import java.time.Instant

import ch.epfl.bluebrain.nexus.delta.sdk.Permissions.{acls, permissions}
import ch.epfl.bluebrain.nexus.delta.sdk.model.Label
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.PermissionsCommand._
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.PermissionsEvent._
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.PermissionsRejection._
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.PermissionsState.{Current, Initial}
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions._
import ch.epfl.bluebrain.nexus.testkit.{IOFixedClock, IOValues}
import monix.execution.Scheduler
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class PermissionsSpec extends AnyWordSpecLike with Matchers with IOValues with IOFixedClock {

  "The Permissions next function" should {
    val minimum     = Set(permissions.write, permissions.read)
    val appended    = Set(acls.write, acls.read)
    val subtracted  = Set(acls.write)
    val unknown     = Set(Permission.unsafe("unknown/unknown"))
    val epoch       = Instant.EPOCH
    val instantNext = epoch.plusMillis(1L)
    val subject     = Identity.User("user", Label.unsafe("realm"))
    val subjectNext = Identity.User("next-user", Label.unsafe("realm"))

    implicit val scheduler: Scheduler = Scheduler.global

    val next = Permissions.next(minimum) _
    val eval = Permissions.evaluate(minimum) _

    "compute the next state" when {
      "state is Initial and event is PermissionsAppended" in {
        val event    = PermissionsAppended(1L, appended, epoch, subject)
        val expected = Current(1L, minimum ++ appended, epoch, subject, epoch, subject)
        next(Initial, event) shouldEqual expected
      }
      "state is Initial and event is PermissionsSubtracted" in {
        val event    = PermissionsSubtracted(1L, subtracted, epoch, subject)
        val expected = Current(1L, minimum, epoch, subject, epoch, subject)
        next(Initial, event) shouldEqual expected
      }
      "state is Initial and event is PermissionsDeleted" in {
        val event    = PermissionsDeleted(1L, epoch, subject)
        val expected = Current(1L, minimum, epoch, subject, epoch, subject)
        next(Initial, event) shouldEqual expected
      }
      "state is Initial and event is PermissionsReplaced" in {
        val event    = PermissionsReplaced(1L, appended, epoch, subject)
        val expected = Current(1L, minimum ++ appended, epoch, subject, epoch, subject)
        next(Initial, event) shouldEqual expected
      }
      "state is Current and event is PermissionsAppended" in {
        val state    = Current(1L, minimum, epoch, subject, epoch, subject)
        val event    = PermissionsAppended(2L, appended, instantNext, subjectNext)
        val expected = Current(2L, minimum ++ appended, epoch, subject, instantNext, subjectNext)
        next(state, event) shouldEqual expected
      }
      "state is Current and event is PermissionsSubtracted" in {
        val state    = Current(1L, minimum ++ appended, epoch, subject, epoch, subject)
        val event    = PermissionsSubtracted(2L, subtracted, instantNext, subjectNext)
        val expected = Current(2L, appended -- subtracted ++ minimum, epoch, subject, instantNext, subjectNext)
        next(state, event) shouldEqual expected
      }
      "state is Current and event is PermissionsDeleted" in {
        val state    = Current(1L, minimum ++ appended, epoch, subject, epoch, subject)
        val event    = PermissionsDeleted(2L, instantNext, subjectNext)
        val expected = Current(2L, minimum, epoch, subject, instantNext, subjectNext)
        next(state, event) shouldEqual expected
      }
      "state is Current and event is PermissionsReplaced" in {
        val state    = Current(1L, minimum ++ appended, epoch, subject, epoch, subject)
        val event    = PermissionsReplaced(2L, subtracted, instantNext, subjectNext)
        val expected = Current(2L, minimum ++ subtracted, epoch, subject, instantNext, subjectNext)
        next(state, event) shouldEqual expected
      }
    }

    "reject with IncorrectRev" when {
      "state is initial and command is ReplacePermissions" in {
        val state    = Initial
        val cmd      = ReplacePermissions(1L, appended, subjectNext)
        val expected = IncorrectRev(1L, 0L)
        eval(state, cmd).rejected shouldEqual expected
      }
      "state is initial and command is AppendPermissions" in {
        val state    = Initial
        val cmd      = AppendPermissions(1L, appended, subjectNext)
        val expected = IncorrectRev(1L, 0L)
        eval(state, cmd).rejected shouldEqual expected
      }
      "state is initial and command is SubtractPermissions" in {
        val state    = Initial
        val cmd      = SubtractPermissions(1L, subtracted, subjectNext)
        val expected = IncorrectRev(1L, 0L)
        eval(state, cmd).rejected shouldEqual expected
      }
      "state is initial and command is DeletePermissions" in {
        val state    = Initial
        val cmd      = DeletePermissions(1L, subjectNext)
        val expected = IncorrectRev(1L, 0L)
        eval(state, cmd).rejected shouldEqual expected
      }
      "state is current and command is ReplacePermissions" in {
        val state    = Current(1L, minimum, epoch, subject, epoch, subject)
        val cmd      = ReplacePermissions(2L, appended, subjectNext)
        val expected = IncorrectRev(2L, 1L)
        eval(state, cmd).rejected shouldEqual expected
      }
      "state is current and command is AppendPermissions" in {
        val state    = Current(1L, minimum, epoch, subject, epoch, subject)
        val cmd      = AppendPermissions(2L, appended, subjectNext)
        val expected = IncorrectRev(2L, 1L)
        eval(state, cmd).rejected shouldEqual expected
      }
      "state is current and command is SubtractPermissions" in {
        val state    = Current(1L, minimum, epoch, subject, epoch, subject)
        val cmd      = SubtractPermissions(2L, subtracted, subjectNext)
        val expected = IncorrectRev(2L, 1L)
        eval(state, cmd).rejected shouldEqual expected
      }
      "state is current and command is DeletePermissions" in {
        val state    = Current(1L, minimum, epoch, subject, epoch, subject)
        val cmd      = DeletePermissions(2L, subjectNext)
        val expected = IncorrectRev(2L, 1L)
        eval(state, cmd).rejected shouldEqual expected
      }
    }

    "reject with CannotReplaceWithEmptyCollection" when {
      "the provided permission set is empty" in {
        val state = Current(1L, minimum, epoch, subject, epoch, subject)
        val cmd   = ReplacePermissions(1L, Set.empty, subjectNext)
        eval(state, cmd).rejected shouldEqual CannotReplaceWithEmptyCollection
      }
      "the provided permission set is minimum" in {
        val state = Current(1L, minimum, epoch, subject, epoch, subject)
        val cmd   = ReplacePermissions(1L, minimum, subjectNext)
        eval(state, cmd).rejected shouldEqual CannotReplaceWithEmptyCollection
      }
    }

    "reject with CannotAppendEmptyCollection" when {
      "the provided permission set is empty" in {
        val state = Current(1L, minimum, epoch, subject, epoch, subject)
        val cmd   = AppendPermissions(1L, Set.empty, subjectNext)
        eval(state, cmd).rejected shouldEqual CannotAppendEmptyCollection
      }
      "the provided permission set is minimum while state is initial" in {
        val state = Initial
        val cmd   = AppendPermissions(0L, minimum, subjectNext)
        eval(state, cmd).rejected shouldEqual CannotAppendEmptyCollection
      }
      "the provided permission set is minimum while state is current" in {
        val state = Current(1L, minimum, epoch, subject, epoch, subject)
        val cmd   = AppendPermissions(1L, minimum, subjectNext)
        eval(state, cmd).rejected shouldEqual CannotAppendEmptyCollection
      }
      "the provided permission set is a subset of the current permissions" in {
        val state = Current(1L, minimum ++ appended, epoch, subject, epoch, subject)
        val cmd   = AppendPermissions(1L, appended, subjectNext)
        eval(state, cmd).rejected shouldEqual CannotAppendEmptyCollection
      }
    }

    "reject with CannotSubtractEmptyCollection" when {
      "the provided permission set is empty" in {
        val state = Current(1L, minimum, epoch, subject, epoch, subject)
        val cmd   = SubtractPermissions(1L, Set.empty, subjectNext)
        eval(state, cmd).rejected shouldEqual CannotSubtractEmptyCollection
      }
    }

    "reject with CannotSubtractFromMinimumCollection" when {
      "the provided permission set is minimum and state is initial" in {
        val state = Initial
        val cmd   = SubtractPermissions(0L, minimum, subjectNext)
        eval(state, cmd).rejected shouldEqual CannotSubtractFromMinimumCollection(minimum)
      }
      "the provided permission set is minimum and state has more permissions" in {
        val state = Current(1L, minimum ++ appended, epoch, subject, epoch, subject)
        val cmd   = SubtractPermissions(1L, minimum, subjectNext)
        eval(state, cmd).rejected shouldEqual CannotSubtractFromMinimumCollection(minimum)
      }
      "the provided permission set is minimum" in {
        val state = Current(1L, minimum, epoch, subject, epoch, subject)
        val cmd   = SubtractPermissions(1L, minimum, subjectNext)
        eval(state, cmd).rejected shouldEqual CannotSubtractFromMinimumCollection(minimum)
      }
    }

    "reject with CannotSubtractUndefinedPermissions" when {
      "the provided permissions are not included in the set" in {
        val state = Current(1L, minimum ++ appended, epoch, subject, epoch, subject)
        val cmd   = SubtractPermissions(1L, minimum ++ subtracted ++ unknown, subjectNext)
        eval(state, cmd).rejected shouldEqual CannotSubtractUndefinedPermissions(unknown)
      }
    }

    "reject with CannotDeleteMinimumCollection" when {
      "the state is initial" in {
        val state = Initial
        val cmd   = DeletePermissions(0L, subjectNext)
        eval(state, cmd).rejected shouldEqual CannotDeleteMinimumCollection
      }
      "the current permission set is the minimum" in {
        val state = Current(1L, minimum, epoch, subject, epoch, subject)
        val cmd   = DeletePermissions(1L, subjectNext)
        eval(state, cmd).rejected shouldEqual CannotDeleteMinimumCollection
      }
    }

    "replace permissions" when {
      "the state is initial" in {
        val state = Initial
        val cmd   = ReplacePermissions(0L, appended, subjectNext)
        eval(state, cmd).accepted shouldEqual PermissionsReplaced(1L, appended, epoch, subjectNext)
      }
      "the state is current" in {
        val state = Current(1L, minimum, epoch, subject, epoch, subject)
        val cmd   = ReplacePermissions(1L, appended, subjectNext)
        eval(state, cmd).accepted shouldEqual PermissionsReplaced(2L, appended, epoch, subjectNext)
      }
    }

    "append permissions" when {
      "the state is initial" in {
        val state = Initial
        val cmd   = AppendPermissions(0L, appended, subjectNext)
        eval(state, cmd).accepted shouldEqual PermissionsAppended(1L, appended, epoch, subjectNext)
      }
      "the state is current" in {
        val state = Current(1L, minimum, epoch, subject, epoch, subject)
        val cmd   = AppendPermissions(1L, minimum ++ appended, subjectNext)
        eval(state, cmd).accepted shouldEqual PermissionsAppended(2L, appended, epoch, subjectNext)
      }
    }

    "subtract permissions" when {
      "the state is current" in {
        val state = Current(1L, minimum ++ appended, epoch, subject, epoch, subject)
        val cmd   = SubtractPermissions(1L, minimum ++ subtracted, subjectNext)
        eval(state, cmd).accepted shouldEqual PermissionsSubtracted(2L, subtracted, epoch, subjectNext)
      }
    }

    "delete permissions" when {
      "the state is current" in {
        val state = Current(1L, minimum ++ appended, epoch, subject, epoch, subject)
        val cmd   = DeletePermissions(1L, subjectNext)
        eval(state, cmd).accepted shouldEqual PermissionsDeleted(2L, epoch, subjectNext)
      }
    }
  }
}
