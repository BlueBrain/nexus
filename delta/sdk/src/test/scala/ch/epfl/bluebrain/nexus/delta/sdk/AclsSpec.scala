package ch.epfl.bluebrain.nexus.delta.sdk

import ch.epfl.bluebrain.nexus.delta.sdk.acls.Acls.{evaluate, next}
import ch.epfl.bluebrain.nexus.delta.sdk.acls.{AclFixtures, Acls}
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.{Acl, AclCommand, AclEvent, AclRejection, AclState}
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.AclAddress.Root
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.AclCommand.{AppendAcl, DeleteAcl, ReplaceAcl, SubtractAcl}
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.AclEvent.{AclAppended, AclDeleted, AclReplaced, AclSubtracted}
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.AclRejection._
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.model.Permission
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Anonymous, User}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import ch.epfl.bluebrain.nexus.testkit.{EitherValuable, IOFixedClock, IOValues}
import monix.bio.{IO, UIO}
import monix.execution.Scheduler
import org.scalatest.{Inspectors, OptionValues}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import java.time.Instant

class AclsSpec
    extends AnyWordSpecLike
    with Matchers
    with EitherValuable
    with OptionValues
    with AclFixtures
    with Inspectors
    with IOFixedClock
    with IOValues {

  "The ACL state machine" when {
    implicit val sc: Scheduler                                             = Scheduler.global
    val currentRealms                                                      = Set(realm, realm2)
    val fetchPermissionsSet                                                = UIO.pure(rwx)
    val findUnknownRealms                                                  = Acls.findUnknownRealms(_, currentRealms)
    val current                                                            = AclState(userR_groupX(Root), 1, epoch, Anonymous, epoch, Anonymous)
    val time2                                                              = Instant.ofEpochMilli(10L)
    val eval: (Option[AclState], AclCommand) => IO[AclRejection, AclEvent] =
      evaluate(fetchPermissionsSet, findUnknownRealms)(_, _)

    "evaluating an incoming command" should {

      "create a new event" in {
        eval(None, ReplaceAcl(groupR(Root), 0, subject)).accepted shouldEqual
          AclReplaced(groupR(Root), 1, epoch, subject)

        eval(None, AppendAcl(groupR(Root), 0, subject)).accepted shouldEqual
          AclAppended(groupR(Root), 1, epoch, subject)

        eval(Some(current), ReplaceAcl(userW(Root), 1, subject)).accepted shouldEqual
          AclReplaced(userW(Root), 2, epoch, subject)

        eval(Some(current), AppendAcl(userW(Root), 1, subject)).accepted shouldEqual
          AclAppended(userW(Root), 2, epoch, subject)

        eval(Some(current), SubtractAcl(groupX(Root), 1, subject)).accepted shouldEqual
          AclSubtracted(groupX(Root), 2, epoch, subject)

        eval(Some(current), DeleteAcl(Root, 1, subject)).accepted shouldEqual
          AclDeleted(Root, 2, epoch, subject)
      }

      "reject with IncorrectRev" in {
        val list = List(
          None          -> ReplaceAcl(groupR(Root), 1, subject),
          Some(current) -> ReplaceAcl(groupR(Root), 2, subject),
          None          -> AppendAcl(groupR(Root), 1, subject),
          Some(current) -> AppendAcl(groupR(Root), 2, subject),
          Some(current) -> SubtractAcl(groupR(Root), 2, subject),
          Some(current) -> DeleteAcl(Root, 2, subject)
        )
        forAll(list) { case (state, cmd) =>
          eval(state, cmd).rejectedWith[IncorrectRev]
        }
      }

      "reject with AclIsEmpty" in {
        eval(Some(current.copy(acl = Acl(Root))), DeleteAcl(Root, 1, subject)).rejectedWith[AclIsEmpty]
      }

      "reject with AclNotFound" in {
        val list = List(
          None -> SubtractAcl(groupR(Root), 0, subject),
          None -> DeleteAcl(Root, 0, subject)
        )
        forAll(list) { case (state, cmd) =>
          eval(state, cmd).rejectedWith[AclNotFound]
        }
      }

      "reject with AclCannotContainEmptyPermissionCollection" in {
        val someEmptyPerms = groupR(Root) ++ Acl(Root, subject -> Set.empty[Permission])
        val list           = List(
          None          -> ReplaceAcl(someEmptyPerms, 0, subject),
          None          -> AppendAcl(someEmptyPerms, 0, subject),
          Some(current) -> ReplaceAcl(someEmptyPerms, 1, subject),
          Some(current) -> AppendAcl(someEmptyPerms, 1, subject),
          Some(current) -> SubtractAcl(someEmptyPerms, 1, subject)
        )
        forAll(list) { case (state, cmd) =>
          eval(state, cmd).rejectedWith[AclCannotContainEmptyPermissionCollection]
        }
      }

      "reject with NothingToBeUpdated" in {
        val list = List(
          current -> AppendAcl(userR_groupX(Root), 1, subject),
          current -> SubtractAcl(anonR(Root), 1, subject)
        )
        forAll(list) { case (state, cmd) =>
          eval(Some(state), cmd).rejectedWith[NothingToBeUpdated]
        }
      }

      "reject with UnknownPermissions" in {
        val unknownPermsAcl = Acl(Root, group -> Set(Permission.unsafe("other")))
        val list            = List(
          None          -> ReplaceAcl(unknownPermsAcl, 0, subject),
          None          -> AppendAcl(unknownPermsAcl, 0, subject),
          Some(current) -> ReplaceAcl(unknownPermsAcl, 1, subject),
          Some(current) -> AppendAcl(unknownPermsAcl, 1, subject)
        )
        forAll(list) { case (state, cmd) =>
          eval(state, cmd).rejectedWith[UnknownPermissions]
        }
      }

      "reject with UnknownRealms" in {
        val realm  = Label.unsafe("other-realm")
        val realm2 = Label.unsafe("other-realm2")
        val acl    = Acl(Root, User("myuser", realm) -> Set(r), User("myuser2", realm2) -> Set(r))
        val list   = List(
          None          -> ReplaceAcl(acl, 0, subject),
          None          -> AppendAcl(acl, 0, subject),
          Some(current) -> ReplaceAcl(acl, 1, subject),
          Some(current) -> AppendAcl(acl, 1, subject)
        )
        forAll(list) { case (state, cmd) =>
          eval(state, cmd).rejectedWith[UnknownRealms] shouldEqual UnknownRealms(Set(realm, realm2))
        }
      }
    }

    "producing next state" should {

      "create a new AclReplaced state" in {
        next(None, AclReplaced(userW(Root), 1, time2, subject)).value shouldEqual
          AclState(userW(Root), 1, time2, subject, time2, subject)

        next(Some(current), AclReplaced(userW(Root), 1, time2, subject)).value shouldEqual
          AclState(userW(Root), 1, epoch, Anonymous, time2, subject)
      }

      "create new AclAppended state" in {
        next(None, AclAppended(userW(Root), 1, time2, subject)).value shouldEqual
          AclState(userW(Root), 1, time2, subject, time2, subject)

        next(Some(current), AclAppended(userW(Root), 1, time2, subject)).value shouldEqual
          AclState(userRW_groupX(Root), 1, epoch, Anonymous, time2, subject)
      }

      "create new AclSubtracted state" in {
        next(None, AclSubtracted(groupX(Root), 1, epoch, subject)) shouldEqual None

        next(Some(current), AclSubtracted(groupX(Root), 1, time2, subject)).value shouldEqual
          AclState(userR(Root), 1, epoch, Anonymous, time2, subject)
      }

      "create new AclDeleted state" in {
        next(None, AclDeleted(Root, 1, epoch, subject)) shouldEqual None

        next(Some(current), AclDeleted(Root, 1, time2, subject)).value shouldEqual
          AclState(Acl(Root), 1, epoch, Anonymous, time2, subject)
      }
    }
  }
}
