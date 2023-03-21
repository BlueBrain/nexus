package ch.epfl.bluebrain.nexus.delta.sdk.permissions

import ch.epfl.bluebrain.nexus.delta.sdk.permissions.Permissions.{acls, permissions}
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.model.PermissionsCommand._
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.model.PermissionsEvent._
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.model.PermissionsRejection._
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.model._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Identity, Label}
import ch.epfl.bluebrain.nexus.testkit.{IOFixedClock, IOValues}
import monix.execution.Scheduler
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import java.time.Instant

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

    val initial = PermissionsState.initial(minimum)
    val next    = Permissions.next(minimum) _
    val eval    = Permissions.evaluate(minimum) _

    "compute the next state" when {
      "state is Initial and event is PermissionsAppended" in {
        val event    = PermissionsAppended(1, appended, epoch, subject)
        val expected = PermissionsState(1, minimum ++ appended, epoch, subject, epoch, subject)
        next(initial, event) shouldEqual expected
      }
      "state is Initial and event is PermissionsSubtracted" in {
        val event    = PermissionsSubtracted(1, subtracted, epoch, subject)
        val expected = PermissionsState(1, minimum, epoch, subject, epoch, subject)
        next(initial, event) shouldEqual expected
      }
      "state is Initial and event is PermissionsDeleted" in {
        val event    = PermissionsDeleted(1, epoch, subject)
        val expected = PermissionsState(1, minimum, epoch, subject, epoch, subject)
        next(initial, event) shouldEqual expected
      }
      "state is Initial and event is PermissionsReplaced" in {
        val event    = PermissionsReplaced(1, appended, epoch, subject)
        val expected = PermissionsState(1, minimum ++ appended, epoch, subject, epoch, subject)
        next(initial, event) shouldEqual expected
      }
      "state is Current and event is PermissionsAppended" in {
        val state    = PermissionsState(1, minimum, epoch, subject, epoch, subject)
        val event    = PermissionsAppended(2, appended, instantNext, subjectNext)
        val expected = PermissionsState(2, minimum ++ appended, epoch, subject, instantNext, subjectNext)
        next(state, event) shouldEqual expected
      }
      "state is Current and event is PermissionsSubtracted" in {
        val state    = PermissionsState(1, minimum ++ appended, epoch, subject, epoch, subject)
        val event    = PermissionsSubtracted(2, subtracted, instantNext, subjectNext)
        val expected = PermissionsState(2, appended -- subtracted ++ minimum, epoch, subject, instantNext, subjectNext)
        next(state, event) shouldEqual expected
      }
      "state is Current and event is PermissionsDeleted" in {
        val state    = PermissionsState(1, minimum ++ appended, epoch, subject, epoch, subject)
        val event    = PermissionsDeleted(2, instantNext, subjectNext)
        val expected = PermissionsState(2, minimum, epoch, subject, instantNext, subjectNext)
        next(state, event) shouldEqual expected
      }
      "state is Current and event is PermissionsReplaced" in {
        val state    = PermissionsState(1, minimum ++ appended, epoch, subject, epoch, subject)
        val event    = PermissionsReplaced(2, subtracted, instantNext, subjectNext)
        val expected = PermissionsState(2, minimum ++ subtracted, epoch, subject, instantNext, subjectNext)
        next(state, event) shouldEqual expected
      }
    }

    "reject with IncorrectRev" when {
      "state is initial and command is ReplacePermissions" in {
        val state    = initial
        val cmd      = ReplacePermissions(1, appended, subjectNext)
        val expected = IncorrectRev(1, 0)
        eval(state, cmd).rejected shouldEqual expected
      }
      "state is initial and command is AppendPermissions" in {
        val state    = initial
        val cmd      = AppendPermissions(1, appended, subjectNext)
        val expected = IncorrectRev(1, 0)
        eval(state, cmd).rejected shouldEqual expected
      }
      "state is initial and command is SubtractPermissions" in {
        val state    = initial
        val cmd      = SubtractPermissions(1, subtracted, subjectNext)
        val expected = IncorrectRev(1, 0)
        eval(state, cmd).rejected shouldEqual expected
      }
      "state is initial and command is DeletePermissions" in {
        val state    = initial
        val cmd      = DeletePermissions(1, subjectNext)
        val expected = IncorrectRev(1, 0)
        eval(state, cmd).rejected shouldEqual expected
      }
      "state is current and command is ReplacePermissions" in {
        val state    = PermissionsState(1, minimum, epoch, subject, epoch, subject)
        val cmd      = ReplacePermissions(2, appended, subjectNext)
        val expected = IncorrectRev(2, 1)
        eval(state, cmd).rejected shouldEqual expected
      }
      "state is current and command is AppendPermissions" in {
        val state    = PermissionsState(1, minimum, epoch, subject, epoch, subject)
        val cmd      = AppendPermissions(2, appended, subjectNext)
        val expected = IncorrectRev(2, 1)
        eval(state, cmd).rejected shouldEqual expected
      }
      "state is current and command is SubtractPermissions" in {
        val state    = PermissionsState(1, minimum, epoch, subject, epoch, subject)
        val cmd      = SubtractPermissions(2, subtracted, subjectNext)
        val expected = IncorrectRev(2, 1)
        eval(state, cmd).rejected shouldEqual expected
      }
      "state is current and command is DeletePermissions" in {
        val state    = PermissionsState(1, minimum, epoch, subject, epoch, subject)
        val cmd      = DeletePermissions(2, subjectNext)
        val expected = IncorrectRev(2, 1)
        eval(state, cmd).rejected shouldEqual expected
      }
    }

    "reject with CannotReplaceWithEmptyCollection" when {
      "the provided permission set is empty" in {
        val state = PermissionsState(1, minimum, epoch, subject, epoch, subject)
        val cmd   = ReplacePermissions(1, Set.empty, subjectNext)
        eval(state, cmd).rejected shouldEqual CannotReplaceWithEmptyCollection
      }
      "the provided permission set is minimum" in {
        val state = PermissionsState(1, minimum, epoch, subject, epoch, subject)
        val cmd   = ReplacePermissions(1, minimum, subjectNext)
        eval(state, cmd).rejected shouldEqual CannotReplaceWithEmptyCollection
      }
    }

    "reject with CannotAppendEmptyCollection" when {
      "the provided permission set is empty" in {
        val state = PermissionsState(1, minimum, epoch, subject, epoch, subject)
        val cmd   = AppendPermissions(1, Set.empty, subjectNext)
        eval(state, cmd).rejected shouldEqual CannotAppendEmptyCollection
      }
      "the provided permission set is minimum while state is initial" in {
        val state = initial
        val cmd   = AppendPermissions(0, minimum, subjectNext)
        eval(state, cmd).rejected shouldEqual CannotAppendEmptyCollection
      }
      "the provided permission set is minimum while state is current" in {
        val state = PermissionsState(1, minimum, epoch, subject, epoch, subject)
        val cmd   = AppendPermissions(1, minimum, subjectNext)
        eval(state, cmd).rejected shouldEqual CannotAppendEmptyCollection
      }
      "the provided permission set is a subset of the current permissions" in {
        val state = PermissionsState(1, minimum ++ appended, epoch, subject, epoch, subject)
        val cmd   = AppendPermissions(1, appended, subjectNext)
        eval(state, cmd).rejected shouldEqual CannotAppendEmptyCollection
      }
    }

    "reject with CannotSubtractEmptyCollection" when {
      "the provided permission set is empty" in {
        val state = PermissionsState(1, minimum, epoch, subject, epoch, subject)
        val cmd   = SubtractPermissions(1, Set.empty, subjectNext)
        eval(state, cmd).rejected shouldEqual CannotSubtractEmptyCollection
      }
    }

    "reject with CannotSubtractFromMinimumCollection" when {
      "the provided permission set is minimum and state is initial" in {
        val state = initial
        val cmd   = SubtractPermissions(0, minimum, subjectNext)
        eval(state, cmd).rejected shouldEqual CannotSubtractFromMinimumCollection(minimum)
      }
      "the provided permission set is minimum and state has more permissions" in {
        val state = PermissionsState(1, minimum ++ appended, epoch, subject, epoch, subject)
        val cmd   = SubtractPermissions(1, minimum, subjectNext)
        eval(state, cmd).rejected shouldEqual CannotSubtractFromMinimumCollection(minimum)
      }
      "the provided permission set is minimum" in {
        val state = PermissionsState(1, minimum, epoch, subject, epoch, subject)
        val cmd   = SubtractPermissions(1, minimum, subjectNext)
        eval(state, cmd).rejected shouldEqual CannotSubtractFromMinimumCollection(minimum)
      }
    }

    "reject with CannotSubtractUndefinedPermissions" when {
      "the provided permissions are not included in the set" in {
        val state = PermissionsState(1, minimum ++ appended, epoch, subject, epoch, subject)
        val cmd   = SubtractPermissions(1, minimum ++ subtracted ++ unknown, subjectNext)
        eval(state, cmd).rejected shouldEqual CannotSubtractUndefinedPermissions(unknown)
      }
    }

    "reject with CannotDeleteMinimumCollection" when {
      "the state is initial" in {
        val state = initial
        val cmd   = DeletePermissions(0, subjectNext)
        eval(state, cmd).rejected shouldEqual CannotDeleteMinimumCollection
      }
      "the current permission set is the minimum" in {
        val state = PermissionsState(1, minimum, epoch, subject, epoch, subject)
        val cmd   = DeletePermissions(1, subjectNext)
        eval(state, cmd).rejected shouldEqual CannotDeleteMinimumCollection
      }
    }

    "replace permissions" when {
      "the state is initial" in {
        val state = initial
        val cmd   = ReplacePermissions(0, appended, subjectNext)
        eval(state, cmd).accepted shouldEqual PermissionsReplaced(1, appended, epoch, subjectNext)
      }
      "the state is current" in {
        val state = PermissionsState(1, minimum, epoch, subject, epoch, subject)
        val cmd   = ReplacePermissions(1, appended, subjectNext)
        eval(state, cmd).accepted shouldEqual PermissionsReplaced(2, appended, epoch, subjectNext)
      }
    }

    "append permissions" when {
      "the state is initial" in {
        val state = initial
        val cmd   = AppendPermissions(0, appended, subjectNext)
        eval(state, cmd).accepted shouldEqual PermissionsAppended(1, appended, epoch, subjectNext)
      }
      "the state is current" in {
        val state = PermissionsState(1, minimum, epoch, subject, epoch, subject)
        val cmd   = AppendPermissions(1, minimum ++ appended, subjectNext)
        eval(state, cmd).accepted shouldEqual PermissionsAppended(2, appended, epoch, subjectNext)
      }
    }

    "subtract permissions" when {
      "the state is current" in {
        val state = PermissionsState(1, minimum ++ appended, epoch, subject, epoch, subject)
        val cmd   = SubtractPermissions(1, minimum ++ subtracted, subjectNext)
        eval(state, cmd).accepted shouldEqual PermissionsSubtracted(2, subtracted, epoch, subjectNext)
      }
    }

    "delete permissions" when {
      "the state is current" in {
        val state = PermissionsState(1, minimum ++ appended, epoch, subject, epoch, subject)
        val cmd   = DeletePermissions(1, subjectNext)
        eval(state, cmd).accepted shouldEqual PermissionsDeleted(2, epoch, subjectNext)
      }
    }
  }
}
