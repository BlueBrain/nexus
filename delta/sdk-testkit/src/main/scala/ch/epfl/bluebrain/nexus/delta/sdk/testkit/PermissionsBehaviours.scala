package ch.epfl.bluebrain.nexus.delta.sdk.testkit

import java.time.Instant

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{nxv, schemas}
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceRef.Latest
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.{Anonymous, Subject}
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.PermissionsRejection._
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.{Permission, PermissionSet}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Label, ResourceF}
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.PermissionsBehaviours._
import ch.epfl.bluebrain.nexus.delta.sdk.{Permissions, PermissionsResource}
import ch.epfl.bluebrain.nexus.testkit.TestHelpers.genString
import ch.epfl.bluebrain.nexus.testkit.{IOFixedClock, IOValues}
import monix.bio.{IO, Task}
import monix.execution.Scheduler
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.TimeoutException
import scala.concurrent.duration._

/**
  * The collection of behaviours for permissions.
  */
trait PermissionsBehaviours { this: AnyWordSpecLike with Matchers with IOValues with IOFixedClock =>

  implicit def subject: Subject = Identity.User("user", Label.unsafe("realm"))

  implicit def scheduler: Scheduler = Scheduler.global

  /**
    * Create a permissions instance. The instance will be memoized.
    */
  def create: Task[Permissions]

  /**
    * The permissions resource id.
    */
  def resourceId: Iri

  private def resourceFor(
      rev: Long,
      permissions: Set[Permission],
      updatedAt: Instant,
      updatedBy: Subject
  ): PermissionsResource =
    ResourceF(
      id = resourceId,
      rev = rev,
      types = Set(nxv.Permissions),
      deprecated = false,
      createdAt = Instant.EPOCH,
      createdBy = Identity.Anonymous,
      updatedAt = updatedAt,
      updatedBy = updatedBy,
      schema = Latest(schemas.permissions),
      value = PermissionSet(permissions)
    )

  private def retryBackoff[E, A](source: IO[E, A], maxRetries: Int, firstDelay: FiniteDuration): IO[E, A] = {
    source.onErrorHandleWith { ex =>
      if (maxRetries > 0)
        // Recursive call, it's OK as Monix is stack-safe
        retryBackoff(source, maxRetries - 1, firstDelay * 2)
          .delayExecution(firstDelay)
      else
        IO.raiseError(ex)
    }
  }

  "A permissions permissions implementation" should {
    val permissions =
      create
        .timeoutWith(10.seconds, new TimeoutException("Unable to create a permissions instance"))
        .memoizeOnSuccess

    val read: Permission = Permissions.permissions.read

    val perm1: Permission = Permission.unsafe(genString())
    val perm2: Permission = Permission.unsafe(genString())
    val perm3: Permission = Permission.unsafe(genString())
    val perm4: Permission = Permission.unsafe(genString())

    "return its persistence id" in {
      permissions.accepted.persistenceId shouldEqual "permissions-permissions"
    }
    "echo the minimum permissions" in {
      permissions.accepted.minimum shouldEqual minimum
    }
    "return the minimum permissions set" in {
      permissions.accepted.fetchPermissionSet.accepted shouldEqual minimum
    }
    "return the minimum permissions resource" in {
      val expected = resourceFor(0L, minimum, Instant.EPOCH, Anonymous)
      permissions.accepted.fetch.accepted shouldEqual expected
    }
    "fail to delete minimum when initial" in {
      permissions.accepted.delete(0L).rejected shouldEqual CannotDeleteMinimumCollection
    }
    "fail to subtract with incorrect rev" in {
      permissions.accepted.subtract(Set(perm1), 1L).rejected shouldEqual IncorrectRev(1L, 0L)
    }
    "fail to subtract from minimum" in {
      permissions.accepted.subtract(Set(perm1), 0L).rejected shouldEqual CannotSubtractFromMinimumCollection(minimum)
    }
    "fail to subtract undefined permissions" in {
      permissions.accepted.append(Set(perm1), 0L).accepted
      permissions.accepted.fetchPermissionSet.accepted shouldEqual (minimum + perm1)
      permissions.accepted.subtract(Set(perm2), 1L).rejected shouldEqual CannotSubtractUndefinedPermissions(Set(perm2))
    }
    "fail to subtract empty permissions" in {
      permissions.accepted.subtract(Set(), 1L).rejected shouldEqual CannotSubtractEmptyCollection
    }
    "fail to subtract from minimum collection" in {
      permissions.accepted.subtract(Set(read), 1L).rejected shouldEqual CannotSubtractFromMinimumCollection(minimum)
    }
    "subtract a permission" in {
      permissions.accepted.subtract(Set(perm1), 1L).accepted
      permissions.accepted.fetchPermissionSet.accepted shouldEqual minimum
    }
    "fail to append with incorrect rev" in {
      permissions.accepted.append(Set(perm1), 0L).rejected shouldEqual IncorrectRev(0L, 2L)
    }
    "append permissions" in {
      permissions.accepted.append(Set(perm1, perm2), 2L).accepted
      permissions.accepted.fetchPermissionSet.accepted shouldEqual (minimum ++ Set(perm1, perm2))
    }
    "fail to append duplicate permissions" in {
      permissions.accepted.append(Set(perm2), 3L).rejected shouldEqual CannotAppendEmptyCollection
    }
    "fail to append empty permissions" in {
      permissions.accepted.append(Set(), 3L).rejected shouldEqual CannotAppendEmptyCollection
    }
    "fail to replace with incorrect rev" in {
      permissions.accepted.replace(Set(perm3), 1L).rejected shouldEqual IncorrectRev(1L, 3L)
    }
    "fail to replace with empty permissions" in {
      permissions.accepted.replace(Set(), 3L).rejected shouldEqual CannotReplaceWithEmptyCollection
    }
    "fail to replace with subset of minimum" in {
      permissions.accepted.replace(Set(read), 3L).rejected shouldEqual CannotReplaceWithEmptyCollection
    }
    "replace non minimum" in {
      permissions.accepted.replace(Set(perm3, perm4), 3L).accepted
      permissions.accepted.fetchPermissionSet.accepted shouldEqual (minimum ++ Set(perm3, perm4))
    }
    "fail to delete with incorrect rev" in {
      permissions.accepted.delete(2L).rejected shouldEqual IncorrectRev(2L, 4L)
    }
    "delete permissions" in {
      permissions.accepted.delete(4L).accepted
      permissions.accepted.fetchPermissionSet.accepted shouldEqual minimum
    }
    "fail to delete minimum permissions" in {
      permissions.accepted.delete(5L).rejected shouldEqual CannotDeleteMinimumCollection
    }
    "return minimum for revision 0L" in {
      permissions.accepted.fetchAt(0L).accepted.value.permissions shouldEqual minimum
    }
    "return revision for correct rev" in {
      val fetch = retryBackoff(permissions.accepted.fetchAt(4L), 4, 100.milliseconds)
      fetch.accepted.value shouldEqual PermissionSet(minimum ++ Set(perm3, perm4))
    }
    "return none for negative rev" in {
      permissions.accepted.fetchAt(-1L).rejected shouldEqual RevisionNotFound(-1L, 5L)
    }
    "return none for unknown rev" in {
      permissions.accepted.fetchAt(9999L).rejected shouldEqual RevisionNotFound(9999L, 5L)
    }
  }

}

object PermissionsBehaviours {

  import ch.epfl.bluebrain.nexus.delta.sdk.Permissions.{schemas, _}

  /**
    * The collection of minimum permissions.
    */
  val minimum = Set(
    acls.read,
    acls.write,
    permissions.read,
    permissions.write,
    realms.read,
    realms.write,
    events.read,
    orgs.read,
    orgs.write,
    orgs.create,
    projects.read,
    projects.write,
    projects.create,
    resources.read,
    resources.write,
    resolvers.write,
    views.write,
    views.query,
    schemas.write,
    files.write,
    storages.write,
    archives.write
  )

}
