package ch.epfl.bluebrain.nexus.delta.sdk.testkit

import java.time.Instant

import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{nxv, schemas}
import ch.epfl.bluebrain.nexus.delta.sdk.model.Identity.{Anonymous, Subject}
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceRef.Latest
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.PermissionsRejection._
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Identity, Label, ResourceF}
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.PermissionsDummySpec.minimum
import ch.epfl.bluebrain.nexus.delta.sdk.{Permissions, PermissionsResource}
import ch.epfl.bluebrain.nexus.testkit.{IOFixedClock, IOValues, TestHelpers}
import monix.execution.Scheduler
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class PermissionsDummySpec extends AnyWordSpecLike with Matchers with IOValues with TestHelpers with IOFixedClock {

  val read: Permission = Permission.unsafe("permissions/read")

  val perm1: Permission = Permission.unsafe(genString())
  val perm2: Permission = Permission.unsafe(genString())
  val perm3: Permission = Permission.unsafe(genString())
  val perm4: Permission = Permission.unsafe(genString())

  val epoch: Instant            = Instant.EPOCH
  implicit val subject: Subject = Identity.User("user", Label.unsafe("realm"))

  implicit val scheduler: Scheduler = Scheduler.global

  val dummy: Permissions = PermissionsDummy(minimum).accepted

  def resourceFor(
      rev: Long,
      permissions: Set[Permission],
      updatedAt: Instant,
      updatedBy: Subject
  ): PermissionsResource =
    ResourceF(
      id = PermissionsDummy.id,
      rev = rev,
      types = Set(nxv.Permissions),
      deprecated = false,
      createdAt = Instant.EPOCH,
      createdBy = Identity.Anonymous,
      updatedAt = updatedAt,
      updatedBy = updatedBy,
      schema = Latest(schemas.permissions),
      value = permissions
    )

  "A dummy permissions implementation" should {
    "return its persistence id" in {
      dummy.persistenceId shouldEqual "permissions"
    }
    "echo the minimum permissions" in {
      dummy.minimum shouldEqual minimum
    }
    "return the minimum permissions set" in {
      dummy.fetchPermissionSet.accepted shouldEqual minimum
    }
    "return the minimum permissions resource" in {
      val expected = resourceFor(0L, minimum, Instant.EPOCH, Anonymous)
      dummy.fetch.accepted shouldEqual expected
    }
    "fail to delete minimum when initial" in {
      dummy.delete(0L).rejected shouldEqual CannotDeleteMinimumCollection
    }
    "fail to subtract with incorrect rev" in {
      dummy.subtract(Set(perm1), 1L).rejected shouldEqual IncorrectRev(1L, 0L)
    }
    "fail to subtract from minimum" in {
      dummy.subtract(Set(perm1), 0L).rejected shouldEqual CannotSubtractFromMinimumCollection(minimum)
    }
    "fail to subtract undefined permissions" in {
      dummy.append(Set(perm1), 0L).accepted
      dummy.fetchPermissionSet.accepted shouldEqual (minimum + perm1)
      dummy.subtract(Set(perm2), 1L).rejected shouldEqual CannotSubtractUndefinedPermissions(Set(perm2))
    }
    "fail to subtract empty permissions" in {
      dummy.subtract(Set(), 1L).rejected shouldEqual CannotSubtractEmptyCollection
    }
    "fail to subtract from minimum collection" in {
      dummy.subtract(Set(read), 1L).rejected shouldEqual CannotSubtractFromMinimumCollection(minimum)
    }
    "subtract a permission" in {
      dummy.subtract(Set(perm1), 1L).accepted
      dummy.fetchPermissionSet.accepted shouldEqual minimum
    }
    "fail to append with incorrect rev" in {
      dummy.append(Set(perm1), 0L).rejected shouldEqual IncorrectRev(0L, 2L)
    }
    "append permissions" in {
      dummy.append(Set(perm1, perm2), 2L).accepted
      dummy.fetchPermissionSet.accepted shouldEqual (minimum ++ Set(perm1, perm2))
    }
    "fail to append duplicate permissions" in {
      dummy.append(Set(perm2), 3L).rejected shouldEqual CannotAppendEmptyCollection
    }
    "fail to append empty permissions" in {
      dummy.append(Set(), 3L).rejected shouldEqual CannotAppendEmptyCollection
    }
    "fail to replace with incorrect rev" in {
      dummy.replace(Set(perm3), 1L).rejected shouldEqual IncorrectRev(1L, 3L)
    }
    "fail to replace with empty permissions" in {
      dummy.replace(Set(), 3L).rejected shouldEqual CannotReplaceWithEmptyCollection
    }
    "fail to replace with subset of minimum" in {
      dummy.replace(Set(read), 3L).rejected shouldEqual CannotReplaceWithEmptyCollection
    }
    "replace non minimum" in {
      dummy.replace(Set(perm3, perm4), 3L).accepted
      dummy.fetchPermissionSet.accepted shouldEqual (minimum ++ Set(perm3, perm4))
    }
    "fail to delete with incorrect rev" in {
      dummy.delete(2L).rejected shouldEqual IncorrectRev(2L, 4L)
    }
    "delete permissions" in {
      dummy.delete(4L).accepted
      dummy.fetchPermissionSet.accepted shouldEqual minimum
    }
    "fail to delete minimum permissions" in {
      dummy.delete(5L).rejected shouldEqual CannotDeleteMinimumCollection
    }
    "return revision for correct rev" in {
      dummy.fetchAt(4L).accepted.value shouldEqual (minimum ++ Set(perm3, perm4))
    }
    "return none for unknown rev" in {
      dummy.fetchAt(9999L).rejected shouldEqual RevisionNotFound(9999, 5)
    }

  }

}

object PermissionsDummySpec {
  val minimum = Set(
    Permission.unsafe("acls/read"),
    Permission.unsafe("acls/write"),
    Permission.unsafe("permissions/read"),
    Permission.unsafe("permissions/write"),
    Permission.unsafe("realms/read"),
    Permission.unsafe("realms/write"),
    Permission.unsafe("events/read"),
    Permission.unsafe("organizations/read"),
    Permission.unsafe("organizations/write"),
    Permission.unsafe("organizations/create"),
    Permission.unsafe("projects/read"),
    Permission.unsafe("projects/write"),
    Permission.unsafe("projects/create"),
    Permission.unsafe("resources/read"),
    Permission.unsafe("resources/write"),
    Permission.unsafe("resolvers/write"),
    Permission.unsafe("views/query"),
    Permission.unsafe("views/write"),
    Permission.unsafe("schemas/write"),
    Permission.unsafe("files/write"),
    Permission.unsafe("storages/write"),
    Permission.unsafe("archives/write")
  )
}
