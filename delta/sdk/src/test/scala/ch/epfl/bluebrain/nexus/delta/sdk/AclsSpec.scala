package ch.epfl.bluebrain.nexus.delta.sdk

import java.time.Instant

import ch.epfl.bluebrain.nexus.delta.sdk.Acls.{evaluate, next}
import ch.epfl.bluebrain.nexus.delta.sdk.mocks.PermissionsMock
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.AclAddress.Root
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.AclCommand.{AppendAcl, DeleteAcl, ReplaceAcl, SubtractAcl}
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.AclEvent.{AclAppended, AclDeleted, AclReplaced, AclSubtracted}
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.AclRejection._
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.AclState.{Current, Initial}
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.{Acl, AclFixtures}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Anonymous
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.{Permission, PermissionsState}
import ch.epfl.bluebrain.nexus.testkit.{EitherValuable, IOFixedClock, IOValues}
import monix.execution.Scheduler
import org.scalatest.Inspectors
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class AclsSpec
    extends AnyWordSpecLike
    with Matchers
    with EitherValuable
    with AclFixtures
    with Inspectors
    with IOFixedClock
    with IOValues {

  "The ACL state machine" when {
    implicit val sc: Scheduler = Scheduler.global
    val currentPerms           = PermissionsState.Current(1L, rwx, epoch, subject, epoch, subject).toResource(Set.empty)
    val perms                  = new PermissionsMock(currentPerms)
    val current                = Current(userR_groupX(Root), 1L, epoch, Anonymous, epoch, Anonymous)
    val time2                  = Instant.ofEpochMilli(10L)

    "evaluating an incoming command" should {

      "create a new event" in {
        evaluate(perms)(Initial, ReplaceAcl(groupR(Root), 0L, subject)).accepted shouldEqual
          AclReplaced(groupR(Root), 1L, epoch, subject)

        evaluate(perms)(Initial, AppendAcl(groupR(Root), 0L, subject)).accepted shouldEqual
          AclAppended(groupR(Root), 1L, epoch, subject)

        evaluate(perms)(current, ReplaceAcl(userW(Root), 1L, subject)).accepted shouldEqual
          AclReplaced(userW(Root), 2L, epoch, subject)

        evaluate(perms)(current, AppendAcl(userW(Root), 1L, subject)).accepted shouldEqual
          AclAppended(userW(Root), 2L, epoch, subject)

        evaluate(perms)(current, SubtractAcl(groupX(Root), 1L, subject)).accepted shouldEqual
          AclSubtracted(groupX(Root), 2L, epoch, subject)

        evaluate(perms)(current, DeleteAcl(Root, 1L, subject)).accepted shouldEqual
          AclDeleted(Root, 2L, epoch, subject)
      }

      "reject with IncorrectRev" in {
        val list = List(
          Initial -> ReplaceAcl(groupR(Root), 1L, subject),
          current -> ReplaceAcl(groupR(Root), 2L, subject),
          Initial -> AppendAcl(groupR(Root), 1L, subject),
          current -> AppendAcl(groupR(Root), 2L, subject),
          current -> SubtractAcl(groupR(Root), 2L, subject),
          current -> DeleteAcl(Root, 2L, subject)
        )
        forAll(list) { case (state, cmd) =>
          evaluate(perms)(state, cmd).rejectedWith[IncorrectRev]
        }
      }

      "reject with AclIsEmpty" in {
        evaluate(perms)(current.copy(acl = Acl(Root)), DeleteAcl(Root, 1L, subject)).rejectedWith[AclIsEmpty]
      }

      "reject with AclNotFound" in {
        val list = List(
          Initial -> SubtractAcl(groupR(Root), 0L, subject),
          Initial -> DeleteAcl(Root, 0L, subject)
        )
        forAll(list) { case (state, cmd) =>
          evaluate(perms)(state, cmd).rejectedWith[AclNotFound]
        }
      }

      "reject with AclCannotContainEmptyPermissionCollection" in {
        val someEmptyPerms = groupR(Root) ++ Acl(Root, subject -> Set.empty[Permission])
        val list           = List(
          Initial -> ReplaceAcl(someEmptyPerms, 0L, subject),
          Initial -> AppendAcl(someEmptyPerms, 0L, subject),
          current -> ReplaceAcl(someEmptyPerms, 1L, subject),
          current -> AppendAcl(someEmptyPerms, 1L, subject),
          current -> SubtractAcl(someEmptyPerms, 1L, subject)
        )
        forAll(list) { case (state, cmd) =>
          evaluate(perms)(state, cmd).rejectedWith[AclCannotContainEmptyPermissionCollection]
        }
      }

      "reject with NothingToBeUpdated" in {
        val list = List(
          current -> AppendAcl(userR_groupX(Root), 1L, subject),
          current -> SubtractAcl(anonR(Root), 1L, subject)
        )
        forAll(list) { case (state, cmd) =>
          evaluate(perms)(state, cmd).rejectedWith[NothingToBeUpdated]
        }
      }

      "reject with UnknownPermissions" in {
        val unknownPermsAcl = Acl(Root, group -> Set(Permission.unsafe("other")))
        val list            = List(
          Initial -> ReplaceAcl(unknownPermsAcl, 0L, subject),
          Initial -> AppendAcl(unknownPermsAcl, 0L, subject),
          current -> ReplaceAcl(unknownPermsAcl, 1L, subject),
          current -> AppendAcl(unknownPermsAcl, 1L, subject)
        )
        forAll(list) { case (state, cmd) =>
          evaluate(perms)(state, cmd).rejectedWith[UnknownPermissions]
        }
      }

    }

    "producing next state" should {

      "create a new AclReplaced state" in {
        next(Initial, AclReplaced(userW(Root), 1L, time2, subject)) shouldEqual
          Current(userW(Root), 1L, time2, subject, time2, subject)

        next(current, AclReplaced(userW(Root), 1L, time2, subject)) shouldEqual
          Current(userW(Root), 1L, epoch, Anonymous, time2, subject)
      }

      "create new AclAppended state" in {
        next(Initial, AclAppended(userW(Root), 1L, time2, subject)) shouldEqual
          Current(userW(Root), 1L, time2, subject, time2, subject)

        next(current, AclAppended(userW(Root), 1L, time2, subject)) shouldEqual
          Current(userRW_groupX(Root), 1L, epoch, Anonymous, time2, subject)
      }

      "create new AclSubtracted state" in {
        next(Initial, AclSubtracted(groupX(Root), 1L, epoch, subject)) shouldEqual Initial

        next(current, AclSubtracted(groupX(Root), 1L, time2, subject)) shouldEqual
          Current(userR(Root), 1L, epoch, Anonymous, time2, subject)
      }

      "create new AclDeleted state" in {
        next(Initial, AclDeleted(Root, 1L, epoch, subject)) shouldEqual Initial

        next(current, AclDeleted(Root, 1L, time2, subject)) shouldEqual
          Current(Acl(Root), 1L, epoch, Anonymous, time2, subject)
      }
    }
  }
}
