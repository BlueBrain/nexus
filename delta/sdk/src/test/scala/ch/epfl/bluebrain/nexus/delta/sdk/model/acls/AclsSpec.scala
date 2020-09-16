package ch.epfl.bluebrain.nexus.delta.sdk.model.acls

import ch.epfl.bluebrain.nexus.delta.rdf.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.model.Identity.Anonymous
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.AclCommand.{AppendAcl, DeleteAcl, ReplaceAcl, SubtractAcl}
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.AclEvent.{AclAppended, AclDeleted, AclReplaced, AclSubtracted}
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.AclRejection.{
  AclCannotContainEmptyPermissionCollection,
  AclIsEmpty,
  AclNotFound,
  IncorrectRev,
  NothingToBeUpdated,
  UnknownPermissions
}
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.AclState.{Current, Initial}
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.Acls.{evaluate, next}
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.Target.Root
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.{Permission, PermissionsRejection, PermissionsState}
import ch.epfl.bluebrain.nexus.delta.sdk.{Permissions, PermissionsResource}
import ch.epfl.bluebrain.nexus.testkit.{EitherValuable, IOFixedClock, IOValues}
import monix.bio.{IO, UIO}
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
    implicit val sc: Scheduler  = Scheduler.global
    val perms: UIO[Permissions] = IO.pure(MockedPermissions)
    val current                 = Current(Root, userR_groupX, 1L, epoch, Anonymous, epoch, Anonymous)

    "evaluating an incoming command" should {

      "create a new event" in {
        evaluate(perms)(Initial, ReplaceAcl(Root, groupR, 0L, subject)).accepted shouldEqual
          AclReplaced(Root, groupR, 1L, epoch, subject)

        evaluate(perms)(Initial, AppendAcl(Root, groupR, 0L, subject)).accepted shouldEqual
          AclAppended(Root, groupR, 1L, epoch, subject)

        evaluate(perms)(current, ReplaceAcl(Root, userW, 1L, subject)).accepted shouldEqual
          AclReplaced(Root, userW, 2L, epoch, subject)

        evaluate(perms)(current, AppendAcl(Root, userW, 1L, subject)).accepted shouldEqual
          AclAppended(Root, userW, 2L, epoch, subject)

        evaluate(perms)(current, SubtractAcl(Root, groupX, 1L, subject)).accepted shouldEqual
          AclSubtracted(Root, groupX, 2L, epoch, subject)

        evaluate(perms)(current, DeleteAcl(Root, 1L, subject)).accepted shouldEqual
          AclDeleted(Root, 2L, epoch, subject)
      }

      "reject with IncorrectRev" in {
        val list = List(
          Initial -> ReplaceAcl(Root, groupR, 1L, subject),
          current -> ReplaceAcl(Root, groupR, 2L, subject),
          Initial -> AppendAcl(Root, groupR, 1L, subject),
          current -> AppendAcl(Root, groupR, 2L, subject),
          current -> SubtractAcl(Root, groupR, 2L, subject),
          current -> DeleteAcl(Root, 2L, subject)
        )
        forAll(list) {
          case (state, cmd) => evaluate(perms)(state, cmd).rejectedWith[IncorrectRev]
        }
      }

      "reject with AclIsEmpty" in {
        evaluate(perms)(current.copy(acl = Acl.empty), DeleteAcl(Root, 1L, subject)).rejectedWith[AclIsEmpty]
      }

      "reject with AclNotFound" in {
        val list = List(
          Initial -> SubtractAcl(Root, groupR, 0L, subject),
          Initial -> DeleteAcl(Root, 0L, subject)
        )
        forAll(list) {
          case (state, cmd) => evaluate(perms)(state, cmd).rejectedWith[AclNotFound]
        }
      }

      "reject with AclCannotContainEmptyPermissionCollection" in {
        val someEmptyPerms = groupR ++ Acl(user -> Set.empty[Permission])
        val list           = List(
          Initial -> ReplaceAcl(Root, someEmptyPerms, 0L, subject),
          Initial -> AppendAcl(Root, someEmptyPerms, 0L, subject),
          current -> ReplaceAcl(Root, someEmptyPerms, 1L, subject),
          current -> AppendAcl(Root, someEmptyPerms, 1L, subject),
          current -> SubtractAcl(Root, someEmptyPerms, 1L, subject)
        )
        forAll(list) {
          case (state, cmd) => evaluate(perms)(state, cmd).rejectedWith[AclCannotContainEmptyPermissionCollection]
        }
      }

      "reject with NothingToBeUpdated" in {
        val list = List(
          current -> AppendAcl(Root, userR_groupX, 1L, subject),
          current -> SubtractAcl(Root, anonR, 1L, subject)
        )
        forAll(list) {
          case (state, cmd) => evaluate(perms)(state, cmd).rejectedWith[NothingToBeUpdated]
        }
      }

      "reject with UnknownPermissions" in {
        val unknownPerms = Acl(group -> Set(Permission.unsafe("other")))
        val list         = List(
          Initial -> ReplaceAcl(Root, unknownPerms, 0L, subject),
          Initial -> AppendAcl(Root, unknownPerms, 0L, subject),
          current -> ReplaceAcl(Root, unknownPerms, 1L, subject),
          current -> AppendAcl(Root, unknownPerms, 1L, subject)
        )
        forAll(list) {
          case (state, cmd) => evaluate(perms)(state, cmd).rejectedWith[UnknownPermissions]
        }
      }

    }

    "producing next state" should {

      "create a new AclReplaced state" in {
        next(Initial, AclReplaced(Root, userW, 1L, epoch, subject)) shouldEqual
          Current(Root, userW, 1L, epoch, subject, epoch, subject)

        next(current, AclReplaced(Root, userW, 1L, epoch, subject)) shouldEqual
          Current(Root, userW, 1L, epoch, Anonymous, epoch, subject)
      }

      "create new AclAppended state" in {
        next(Initial, AclAppended(Root, userW, 1L, epoch, subject)) shouldEqual
          Current(Root, userW, 1L, epoch, subject, epoch, subject)

        next(current, AclAppended(Root, userW, 1L, epoch, subject)) shouldEqual
          Current(Root, userRW_groupX, 1L, epoch, Anonymous, epoch, subject)
      }

      "create new AclSubtracted state" in {
        next(Initial, AclSubtracted(Root, groupX, 1L, epoch, subject)) shouldEqual Initial

        next(current, AclSubtracted(Root, groupX, 1L, epoch, subject)) shouldEqual
          Current(Root, userR, 1L, epoch, Anonymous, epoch, subject)
      }

      "create new AclDeleted state" in {
        next(Initial, AclDeleted(Root, 1L, epoch, subject)) shouldEqual Initial

        next(current, AclDeleted(Root, 1L, epoch, subject)) shouldEqual
          Current(Root, Acl.empty, 1L, epoch, Anonymous, epoch, subject)
      }
    }
  }
}

object MockedPermissions extends Permissions with AclFixtures {
  val iri = iri"http://example.com/permissions"

  override def persistenceId: String                                                                            = ???
  override def minimum: Set[Permission]                                                                         = ???
  override def fetch: UIO[PermissionsResource]                                                                  =
    IO.pure(PermissionsState.Current(1L, rwx, epoch, subject, epoch, subject).toResource(iri, Set.empty))
  override def fetchAt(rev: Long): IO[PermissionsRejection.RevisionNotFound, PermissionsResource]               = ???
  override def replace(permissions: Set[Permission], rev: Long): IO[PermissionsRejection, PermissionsResource]  = ???
  override def append(permissions: Set[Permission], rev: Long): IO[PermissionsRejection, PermissionsResource]   = ???
  override def subtract(permissions: Set[Permission], rev: Long): IO[PermissionsRejection, PermissionsResource] = ???
  override def delete(rev: Long): IO[PermissionsRejection, PermissionsResource]                                 = ???
}
