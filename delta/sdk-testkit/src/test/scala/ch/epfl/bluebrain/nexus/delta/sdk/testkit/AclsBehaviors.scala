package ch.epfl.bluebrain.nexus.delta.sdk.testkit

import java.time.Instant

import akka.persistence.query.Sequence
import ch.epfl.bluebrain.nexus.delta.sdk.generators.AclGen.resourceFor
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.AclAddress.Organization
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.AclAddressFilter.{AnyOrganization, AnyOrganizationAnyProject, AnyProject}
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.AclEvent.{AclAppended, AclDeleted, AclReplaced, AclSubtracted}
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.AclRejection.{AclCannotContainEmptyPermissionCollection, AclIsEmpty, AclNotFound, NothingToBeUpdated, RevisionNotFound, UnknownPermissions}
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.{Acl, AclAddress, AclCollection, AclState}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.{Anonymous, Group, Subject}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.{Caller, Identity}
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Label}
import ch.epfl.bluebrain.nexus.delta.sdk.{Acls, Permissions}
import ch.epfl.bluebrain.nexus.testkit.{CirceLiteral, IOFixedClock, IOValues, TestHelpers}
import monix.bio.Task
import monix.execution.Scheduler
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.{CancelAfterFailure, Inspectors, OptionValues}

import scala.concurrent.TimeoutException
import scala.concurrent.duration._

trait AclsBehaviors {
  this: AnyWordSpecLike
    with Matchers
    with IOValues
    with TestHelpers
    with IOFixedClock
    with CirceLiteral
    with OptionValues
    with CancelAfterFailure
    with Inspectors =>

  val epoch: Instant                = Instant.EPOCH
  implicit val subject: Subject     = Identity.User("user", Label.unsafe("realm"))
  implicit val caller: Caller       = Caller.unsafe(subject)
  implicit val scheduler: Scheduler = Scheduler.global
  implicit val baseUri: BaseUri     = BaseUri("http://localhost", Label.unsafe("v1"))

  val user: Identity  = subject
  val group: Identity = Group("mygroup", Label.unsafe("myrealm2"))
  val anon: Identity  = Anonymous

  val r: Permission        = Permission.unsafe("acls/read")
  val w: Permission        = Permission.unsafe("acls/write")
  val x: Permission        = Permission.unsafe("organizations/create")
  val rwx: Set[Permission] = Set(r, w, x)

  def userR(address: AclAddress)         = Acl(address, user -> Set(r))
  def userW(address: AclAddress)         = Acl(address, user -> Set(w))
  def userRW(address: AclAddress)        = Acl(address, user -> Set(r, w))
  def userR_groupX(address: AclAddress)  = Acl(address, user -> Set(r), group -> Set(x))
  def userRW_groupX(address: AclAddress) = Acl(address, user -> Set(r, w), group -> Set(x))
  def groupR(address: AclAddress)        = Acl(address, group -> Set(r))
  def groupX(address: AclAddress)        = Acl(address, group -> Set(x))
  def anonR(address: AclAddress)         = Acl(address, anon -> Set(r))

  val org: Label          = Label.unsafe("org")
  val org2: Label         = Label.unsafe("org2")
  val proj: Label         = Label.unsafe("proj")
  val orgTarget           = AclAddress.Organization(org)
  val org2Target          = AclAddress.Organization(org2)
  val projectTarget       = AclAddress.Project(org, proj)
  val any                 = AnyOrganizationAnyProject(false)
  val anyWithAncestors    = AnyOrganizationAnyProject(true)
  val anyOrg              = AnyOrganization(false)
  val anyOrgWithAncestors = AnyOrganization(true)

  def create: Task[(Acls, Permissions)]

  "An ACLs implementation" should {
    val (acls, permissions) =
      create
        .timeoutWith(10.seconds, new TimeoutException("Unable to create a permissions instance"))
        .memoizeOnSuccess
        .accepted

    "return the full permissions for Anonymous if no permissions are defined" in {
      val expected: AclCollection =
        permissions.fetchPermissionSet
          .map(ps => AclCollection(AclState.Initial.toResource(AclAddress.Root, ps).value))
          .accepted
      acls.fetchWithAncestors(projectTarget).accepted shouldEqual expected
      acls.fetchWithAncestors(orgTarget).accepted shouldEqual expected
      acls.fetchWithAncestors(AclAddress.Root).accepted shouldEqual expected
    }

    "append an ACL" in {
      acls.append(userR(AclAddress.Root), 0L).accepted shouldEqual resourceFor(userR(AclAddress.Root), 1L, subject)
    }

    "should not return permissions for Anonymous after a new revision was recorded on Root" in {
      val expected = AclCollection(resourceFor(userR(AclAddress.Root), 1L, subject))
      acls.fetchWithAncestors(projectTarget).accepted shouldEqual expected
      acls.fetchWithAncestors(orgTarget).accepted shouldEqual expected
      acls.fetchWithAncestors(AclAddress.Root).accepted shouldEqual expected
    }

    "replace an ACL" in {
      acls.replace(userR_groupX(AclAddress.Root), 1L).accepted shouldEqual
        resourceFor(userR_groupX(AclAddress.Root), 2L, subject)
    }

    "subtract an ACL" in {
      acls.subtract(groupX(AclAddress.Root), 2L).accepted shouldEqual
        resourceFor(userR(AclAddress.Root), 3L, subject)
    }

    "delete an ACL" in {
      acls.delete(AclAddress.Root, 3L).accepted shouldEqual
        resourceFor(Acl(AclAddress.Root), 4L, subject)
    }

    "fetch an ACL" in {
      acls.replace(userR_groupX(orgTarget), 0L).accepted
      acls.fetch(orgTarget).accepted shouldEqual resourceFor(userR_groupX(orgTarget), 1L, subject)
    }

    "fetch an ACL containing only caller identities" in {
      acls.fetchSelf(orgTarget).accepted shouldEqual resourceFor(userR(orgTarget), 1L, subject)
    }

    "fetch an ACL data" in {
      acls.fetchAcl(orgTarget).accepted shouldEqual userR_groupX(orgTarget)
      acls.fetchSelfAcl(orgTarget).accepted shouldEqual userR(orgTarget)
      acls.fetchAcl(projectTarget).accepted shouldEqual Acl(projectTarget)
    }

    "fetch an ACL at specific revision" in {
      acls.append(userW(orgTarget), 1L).accepted
      acls.fetchAt(orgTarget, 2L).accepted shouldEqual resourceFor(userRW_groupX(orgTarget), 2L, subject)
      acls.fetchAt(orgTarget, 1L).accepted shouldEqual resourceFor(userR_groupX(orgTarget), 1L, subject)
    }

    "fetch an ACL at specific revision containing only caller identities" in {
      acls.fetchSelfAt(orgTarget, 1L).accepted shouldEqual resourceFor(userR(orgTarget), 1L, subject)
    }

    "fail fetching a non existing acl" in {
      val targetNotExist = Organization(Label.unsafe("other"))
      acls.fetch(targetNotExist).rejectedWith[AclNotFound]
    }

    "fail fetching a non existing acl at specific revision" in {
      val targetNotExist = Organization(Label.unsafe("other"))
      acls.fetchAt(targetNotExist, 1L).rejectedWith[AclNotFound]
    }

    "list ACLs" in {
      acls.append(groupR(AclAddress.Root), 4L).accepted
      acls.append(anonR(projectTarget), 0L).accepted

      forAll(List(any, AnyProject(org, withAncestors = false))) { filter =>
        acls.list(filter).accepted shouldEqual AclCollection(resourceFor(anonR(projectTarget), 1L, subject))
      }
    }

    "list ACLs containing only caller identities" in {
      acls.listSelf(anyOrg).accepted shouldEqual AclCollection(resourceFor(userRW(orgTarget), 2L, subject))
    }

    "list ACLs containing ancestors" in {
      acls.append(userRW(org2Target), 0L).accepted

      acls.list(anyWithAncestors).accepted shouldEqual
        AclCollection(
          resourceFor(groupR(AclAddress.Root), 5L, subject),
          resourceFor(userRW_groupX(orgTarget), 2L, subject),
          resourceFor(userRW(org2Target), 1L, subject),
          resourceFor(anonR(projectTarget), 1L, subject)
        )

      acls.list(AnyProject(org, withAncestors = true)).accepted shouldEqual
        AclCollection(
          resourceFor(groupR(AclAddress.Root), 5L, subject),
          resourceFor(userRW_groupX(orgTarget), 2L, subject),
          resourceFor(anonR(projectTarget), 1L, subject)
        )

      acls.list(anyOrgWithAncestors).accepted shouldEqual
        AclCollection(
          resourceFor(groupR(AclAddress.Root), 5L, subject),
          resourceFor(userRW_groupX(orgTarget), 2L, subject),
          resourceFor(userRW(org2Target), 1L, subject)
        )
    }

    "list ACLs containing ancestors and caller identities" in {
      acls.listSelf(anyOrgWithAncestors).accepted shouldEqual
        AclCollection(
          resourceFor(Acl(AclAddress.Root), 5L, subject),
          resourceFor(userRW(orgTarget), 2L, subject),
          resourceFor(userRW(org2Target), 1L, subject)
        )
    }

    "fetch ACLs containing ancestors" in {
      acls.fetchWithAncestors(projectTarget).accepted shouldEqual
        AclCollection(
          resourceFor(groupR(AclAddress.Root), 5L, subject),
          resourceFor(userRW_groupX(orgTarget), 2L, subject),
          resourceFor(anonR(projectTarget), 1L, subject)
        )
    }

    "fetch ACLs containing ancestors at specific revision" in {
      acls.fetchAtWithAncestors(projectTarget, 1L).accepted shouldEqual
        AclCollection(
          resourceFor(userR(AclAddress.Root), 1L, subject),
          resourceFor(userR_groupX(orgTarget), 1L, subject),
          resourceFor(anonR(projectTarget), 1L, subject)
        )
    }

    val allEvents = SSEUtils.list(
      AclAddress.Root -> AclAppended,
      AclAddress.Root -> AclReplaced,
      AclAddress.Root -> AclSubtracted,
      AclAddress.Root -> AclDeleted,
      orgTarget       -> AclReplaced,
      orgTarget       -> AclAppended,
      AclAddress.Root -> AclAppended,
      projectTarget   -> AclAppended,
      org2Target      -> AclAppended
    )

    "get the different events from start" in {
      val events = acls
        .events()
        .map { e => (e.event.address, e.eventType, e.offset) }
        .take(9L)
        .compile
        .toList

      events.accepted shouldEqual allEvents
    }

    "get the different current events from start" in {
      val events = acls
        .currentEvents()
        .map { e => (e.event.address, e.eventType, e.offset) }
        .compile
        .toList

      events.accepted shouldEqual allEvents
    }

    "get the different events from offset 2" in {
      val events = acls
        .events(Sequence(2L))
        .map { e => (e.event.address, e.eventType, e.offset) }
        .take(9L - 2L)
        .compile
        .toList

      events.accepted shouldEqual allEvents.drop(2)
    }

    "get the different current events from offset 2" in {
      val events = acls
        .currentEvents(Sequence(2L))
        .map { e => (e.event.address, e.eventType, e.offset) }
        .compile
        .toList

      events.accepted shouldEqual allEvents.drop(2)
    }

    "fail to fetch an ACL on nonexistent revision" in {
      acls.fetchAt(orgTarget, 10L).rejected shouldEqual RevisionNotFound(10L, 2L)
    }

    "fail to append an ACL already appended" in {
      acls.append(userRW(org2Target), 1L).rejectedWith[NothingToBeUpdated]
    }

    "fail to subtract an ACL with permissions that do not exist" in {
      acls.subtract(anonR(org2Target), 1L).rejectedWith[NothingToBeUpdated]
    }

    "fail to replace an ACL containing empty permissions" in {
      val aclWithEmptyPerms = Acl(org2Target, user -> Set(r), group -> Set.empty)
      acls.replace(aclWithEmptyPerms, 1L).rejectedWith[AclCannotContainEmptyPermissionCollection]
    }

    "fail to append an ACL containing empty permissions" in {
      val aclWithEmptyPerms = Acl(org2Target, user -> Set(r), group -> Set.empty)
      acls.append(aclWithEmptyPerms, 1L).rejectedWith[AclCannotContainEmptyPermissionCollection]
    }

    "fail to subtract an ACL containing empty permissions" in {
      val aclWithEmptyPerms = Acl(org2Target, user -> Set(r), group -> Set.empty)
      acls.subtract(aclWithEmptyPerms, 1L).rejectedWith[AclCannotContainEmptyPermissionCollection]
    }

    "fail to delete an ACL already deleted" in {
      acls.delete(org2Target, 1L).accepted
      acls.delete(org2Target, 2L).rejectedWith[AclIsEmpty]
    }

    "fail to subtract an ACL that does not exist" in {
      val targetNotExist = Organization(Label.unsafe("other"))
      acls.subtract(anonR(targetNotExist), 0L).rejectedWith[AclNotFound]
    }

    "fail to delete an ACL that does not exist" in {
      val targetNotExist = Organization(Label.unsafe("other"))
      acls.delete(targetNotExist, 0L).rejectedWith[AclNotFound]
    }

    "fail to replace an ACL containing invalid permissions" in {
      val aclWithInvalidPerms = Acl(org2Target, user -> Set(r), group -> Set(Permission.unsafe("invalid")))
      acls.replace(aclWithInvalidPerms, 2L).rejectedWith[UnknownPermissions]
    }

    "fail to append an ACL containing invalid permissions" in {
      val aclWithInvalidPerms = Acl(org2Target, user -> Set(r), group -> Set(Permission.unsafe("invalid")))
      acls.append(aclWithInvalidPerms, 2L).rejectedWith[UnknownPermissions]
    }

    "fail to subtract an ACL containing invalid permissions" in {
      val aclWithInvalidPerms = Acl(orgTarget, user -> Set(r), group -> Set(Permission.unsafe("invalid")))
      acls.subtract(aclWithInvalidPerms, 2L).rejectedWith[UnknownPermissions]
    }

    "subtract an ACL correctly" in {
      acls.replace(userRW(AclAddress.Root), 5L).accepted
      acls.subtract(userW(AclAddress.Root), 6L).accepted
      acls.fetch(AclAddress.Root).accepted shouldEqual resourceFor(userR(AclAddress.Root), 7L, subject)
    }
  }

}
