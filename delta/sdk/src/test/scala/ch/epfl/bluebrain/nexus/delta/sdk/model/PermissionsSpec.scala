package ch.epfl.bluebrain.nexus.delta.sdk.model

import java.time.Instant

import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.sdk.Permissions
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.PermissionsCommand._
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.PermissionsEvent._
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.PermissionsRejection._
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.PermissionsState.{Current, Initial}
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions._
import monix.bio.{IO, UIO}
import monix.execution.Scheduler
import monix.execution.schedulers.CanBlock
import org.scalatest.Inspectors
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.duration.TimeUnit

class PermissionsSpec extends AnyWordSpecLike with Matchers with Inspectors {

  "The Permissions next function" should {
    val minimum     = Set(Permission.unsafe("perms/write"), Permission.unsafe("perms/read"))
    val appended    = Set(Permission.unsafe("acls/write"), Permission.unsafe("acls/read"))
    val subtracted  = Set(Permission.unsafe("acls/write"))
    val unknown     = Set(Permission.unsafe("unknown/unknown"))
    val instant     = Instant.ofEpochMilli(1L)
    val instantNext = instant.plusMillis(1L)
    val subject     = Identity.User("user", Label.unsafe("realm"))
    val subjectNext = Identity.User("next-user", Label.unsafe("realm"))

    "compute the next state" in {
      val cases = List[(PermissionsState, PermissionsEvent, PermissionsState)](
        // format: off

        // append with correct rev
        (Initial, PermissionsAppended(1L, appended, instant, subject), Current(1L, minimum ++ appended, instant, subject, instant, subject)),
        // append with incorrect rev
        (Initial, PermissionsAppended(2L, appended, instant, subject), Initial),
        // subtract with correct rev
        (Initial, PermissionsSubtracted(1L, subtracted, instant, subject), Initial),
        // subtract with incorrect rev
        (Initial, PermissionsSubtracted(2L, subtracted, instant, subject), Initial),
        // delete with correct rev
        (Initial, PermissionsDeleted(1L, instant, subject), Initial),
        // delete with incorrect rev
        (Initial, PermissionsDeleted(2L, instant, subject), Initial),
        // replace with correct rev
        (Initial, PermissionsReplaced(1L, appended, instant, subject), Current(1L, minimum ++ appended, instant, subject, instant, subject)),
        // replace with incorrect rev
        (Initial, PermissionsReplaced(2L, appended, instant, subject), Initial),

        // append with correct rev
        (Current(1L, minimum, instant, subject, instant, subject),
          PermissionsAppended(2L, appended, instantNext, subjectNext),
          Current(2L, minimum ++ appended, instant, subject, instantNext, subjectNext)),
        // append with incorrect rev
        (Current(1L, minimum, instant, subject, instant, subject),
          PermissionsAppended(1L, appended, instantNext, subjectNext),
          Current(1L, minimum, instant, subject, instant, subject)),
        // subtract with correct rev
        (Current(1L, minimum ++ appended, instant, subject, instant, subject),
          PermissionsSubtracted(2L, subtracted, instantNext, subjectNext),
          Current(2L, appended -- subtracted ++ minimum, instant, subject, instantNext, subjectNext)),
        // subtract with incorrect rev
        (Current(1L, minimum ++ appended, instant, subject, instant, subject),
          PermissionsSubtracted(1L, subtracted, instantNext, subjectNext),
          Current(1L, minimum ++ appended, instant, subject, instant, subject)),
        // delete with correct rev
        (Current(1L, minimum ++ appended, instant, subject, instant, subject),
          PermissionsDeleted(2L, instantNext, subjectNext),
          Current(2L, minimum, instant, subject, instantNext, subjectNext)),
        // delete with incorrect rev
        (Current(1L, minimum ++ appended, instant, subject, instant, subject),
          PermissionsDeleted(1L, instantNext, subjectNext),
          Current(1L, minimum ++ appended, instant, subject, instant, subject)),
        // replace with correct rev
        (Current(1L, minimum ++ appended, instant, subject, instant, subject),
          PermissionsReplaced(2L, subtracted, instantNext, subjectNext),
          Current(2L, minimum ++ subtracted, instant, subject, instantNext, subjectNext)),
        // replace with incorrect rev
        (Current(1L, minimum ++ appended, instant, subject, instant, subject),
          PermissionsReplaced(1L, subtracted, instantNext, subjectNext),
          Current(1L, minimum ++ appended, instant, subject, instant, subject))

        // format: on
      )

      forAll(cases) {
        case (state, event, expected) =>
          Permissions.next(minimum)(state, event) shouldEqual expected
      }
    }

    "evaluate correctly commands" in {
      val cases = List[(PermissionsState, PermissionsCommand, Either[PermissionsRejection, PermissionsEvent])](
        // format: off

        // replace
        (Initial, ReplacePermissions(1L, appended, subjectNext), Left(IncorrectRev(1L, 0L))),
        (Initial, ReplacePermissions(0L, Set.empty, subjectNext), Left(CannotReplaceWithEmptyCollection)),
        (Initial, ReplacePermissions(0L, minimum, subjectNext), Left(CannotReplaceWithEmptyCollection)),
        (Initial, ReplacePermissions(0L, appended, subjectNext), Right(PermissionsReplaced(1L, appended, instantNext, subjectNext))),
        (Current(1L, minimum, instant, subject, instant, subject), ReplacePermissions(2L, appended, subjectNext), Left(IncorrectRev(2L, 1L))),
        (Current(1L, minimum, instant, subject, instant, subject), ReplacePermissions(1L, appended, subjectNext), Right(PermissionsReplaced(2L, appended, instantNext, subjectNext))),

        // append
        (Initial, AppendPermissions(1L, appended, subjectNext), Left(IncorrectRev(1L, 0L))),
        (Initial, AppendPermissions(0L, Set.empty, subjectNext), Left(CannotAppendEmptyCollection)),
        (Initial, AppendPermissions(0L, minimum, subjectNext), Left(CannotAppendEmptyCollection)),
        (Initial, AppendPermissions(0L, appended, subjectNext), Right(PermissionsAppended(1L, appended, instantNext, subjectNext))),
        (Current(1L, minimum, instant, subject, instant, subject), AppendPermissions(2L, appended, subjectNext), Left(IncorrectRev(2L, 1L))),
        (Current(1L, minimum ++ appended, instant, subject, instant, subject), AppendPermissions(1L, minimum ++ appended, subjectNext), Left(CannotAppendEmptyCollection)),
        (Current(1L, minimum, instant, subject, instant, subject), AppendPermissions(1L, appended, subjectNext), Right(PermissionsAppended(2L, appended, instantNext, subjectNext))),

        // subtract
        (Initial, SubtractPermissions(1L, subtracted, subjectNext), Left(IncorrectRev(1L, 0L))),
        (Initial, SubtractPermissions(0L, Set.empty, subjectNext), Left(CannotSubtractEmptyCollection)),
        (Initial, SubtractPermissions(0L, minimum, subjectNext), Left(CannotSubtractFromMinimumCollection(minimum))),
        (Current(1L, minimum, instant, subject, instant, subject), SubtractPermissions(2L, subtracted, subjectNext), Left(IncorrectRev(2L, 1L))),
        (Current(1L, minimum, instant, subject, instant, subject), SubtractPermissions(1L, Set.empty, subjectNext), Left(CannotSubtractEmptyCollection)),
        (Current(1L, minimum ++ appended, instant, subject, instant, subject), SubtractPermissions(1L, minimum, subjectNext), Left(CannotSubtractFromMinimumCollection(minimum))),
        (Current(1L, minimum ++ appended, instant, subject, instant, subject), SubtractPermissions(1L, minimum ++ subtracted ++ unknown, subjectNext), Left(CannotSubtractUndefinedPermissions(unknown))),
        (Current(1L, minimum ++ appended, instant, subject, instant, subject), SubtractPermissions(1L, minimum ++ subtracted, subjectNext), Right(PermissionsSubtracted(2L, subtracted, instantNext, subjectNext))),

        // delete
        (Initial, DeletePermissions(1L, subjectNext), Left(IncorrectRev(1L, 0L))),
        (Initial, DeletePermissions(0L, subjectNext), Left(CannotDeleteMinimumCollection)),
        (Current(1L, minimum, instant, subject, instant, subject), DeletePermissions(2L, subjectNext), Left(IncorrectRev(2L, 1L))),
        (Current(1L, minimum, instant, subject, instant, subject), DeletePermissions(1L, subjectNext), Left(CannotDeleteMinimumCollection)),
        (Current(1L, minimum ++ appended, instant, subject, instant, subject), DeletePermissions(1L, subjectNext), Right(PermissionsDeleted(2L, instantNext, subjectNext)))
      )

      implicit val scheduler: Scheduler = Scheduler.global
      implicit val permit: CanBlock     = CanBlock.permit
      implicit val fixed2L: Clock[UIO]  = new Clock[UIO] {
        override def realTime(unit: TimeUnit): UIO[Long] = IO.pure(unit.convert(2L, unit))
        override def monotonic(unit: TimeUnit): UIO[Long] = IO.pure(unit.convert(2L, unit))
      }

      forAll(cases) {
        case (state, command, expected) =>
          Permissions.evaluate(minimum)(state, command)(fixed2L).attempt.runSyncUnsafe() shouldEqual expected
      }
    }
  }

}
