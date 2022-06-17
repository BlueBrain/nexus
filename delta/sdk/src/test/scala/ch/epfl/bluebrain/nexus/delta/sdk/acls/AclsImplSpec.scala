package ch.epfl.bluebrain.nexus.delta.sdk.acls

import ch.epfl.bluebrain.nexus.delta.sdk.{ConfigFixtures, SSEUtils}
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.AclAddress.Organization
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.AclAddressFilter.{AnyOrganization, AnyOrganizationAnyProject, AnyProject}
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.AclEvent.{AclAppended, AclDeleted, AclReplaced, AclSubtracted}
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.AclRejection.{AclCannotContainEmptyPermissionCollection, AclIsEmpty, AclNotFound, NothingToBeUpdated, RevisionNotFound, UnknownPermissions}
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.{Acl, AclAddress, AclCollection, AclState}
import ch.epfl.bluebrain.nexus.delta.sdk.generators.AclGen.resourceFor
import ch.epfl.bluebrain.nexus.delta.sdk.generators.PermissionsGen
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.model.Permission
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Anonymous, Group, Subject}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Identity, Label}
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.testkit.{CirceLiteral, DoobieFixture, IOFixedClock, IOValues}
import monix.bio.UIO
import monix.execution.Scheduler
import org.scalatest.{CancelAfterFailure, Inspectors}
import org.scalatest.matchers.should.Matchers

import java.time.Instant

class AclsImplSpec
    extends DoobieFixture
    with IOValues
    with IOFixedClock
    with Inspectors
    with Matchers
    with CancelAfterFailure
    with CirceLiteral
    with ConfigFixtures {

  val epoch: Instant                = Instant.EPOCH
  val realm: Label                  = Label.unsafe("realm")
  val realm2: Label                 = Label.unsafe("myrealm2")
  implicit val subject: Subject     = Identity.User("user", realm)
  implicit val caller: Caller       = Caller.unsafe(subject)
  implicit val scheduler: Scheduler = Scheduler.global
  implicit val baseUri: BaseUri     = BaseUri("http://localhost", Label.unsafe("v1"))

  val user: Identity  = subject
  val group: Identity = Group("mygroup", realm2)
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

  val minimumPermissions: Set[Permission] = PermissionsGen.minimum

  "An ACLs implementation" should {
    lazy val acls: Acls = AclsImpl(
      UIO.pure(minimumPermissions),
      Acls.findUnknownRealms(_, Set(realm, realm2)),
      minimumPermissions,
      AclsConfig(eventLogConfig),
      xas
    )

    "return the full permissions for Anonymous if no permissions are defined" in {
      val expected: AclCollection =
        AclCollection(AclState.initial(minimumPermissions).toResource)
      acls.fetchWithAncestors(projectTarget).accepted shouldEqual expected
      acls.fetchWithAncestors(orgTarget).accepted shouldEqual expected
      acls.fetchWithAncestors(AclAddress.Root).accepted shouldEqual expected
    }

    "append an ACL" in {
      acls.append(userR(AclAddress.Root), 0).accepted shouldEqual resourceFor(userR(AclAddress.Root), 1, subject)
    }

    "should not return permissions for Anonymous after a new revision was recorded on Root" in {
      val expected = AclCollection(resourceFor(userR(AclAddress.Root), 1, subject))
      acls.fetchWithAncestors(projectTarget).accepted shouldEqual expected
      acls.fetchWithAncestors(orgTarget).accepted shouldEqual expected
      acls.fetchWithAncestors(AclAddress.Root).accepted shouldEqual expected
    }

    "replace an ACL" in {
      acls.replace(userR_groupX(AclAddress.Root), 1).accepted shouldEqual
        resourceFor(userR_groupX(AclAddress.Root), 2, subject)
    }

    "subtract an ACL" in {
      acls.subtract(groupX(AclAddress.Root), 2).accepted shouldEqual
        resourceFor(userR(AclAddress.Root), 3, subject)
    }

    "delete an ACL" in {
      acls.delete(AclAddress.Root, 3).accepted shouldEqual
        resourceFor(Acl(AclAddress.Root), 4, subject)
    }

    "fetch an ACL" in {
      acls.replace(userR_groupX(orgTarget), 0).accepted
      acls.fetch(orgTarget).accepted shouldEqual resourceFor(userR_groupX(orgTarget), 1, subject)
    }

    "fetch an ACL containing only caller identities" in {
      acls.fetchSelf(orgTarget).accepted shouldEqual resourceFor(userR(orgTarget), 1, subject)
    }

    "fetch an ACL data" in {
      acls.fetchAcl(orgTarget).accepted shouldEqual userR_groupX(orgTarget)
      acls.fetchSelfAcl(orgTarget).accepted shouldEqual userR(orgTarget)
      acls.fetchAcl(projectTarget).accepted shouldEqual Acl(projectTarget)
    }

    "fetch an ACL at specific revision" in {
      acls.append(userW(orgTarget), 1).accepted
      acls.fetchAt(orgTarget, 2).accepted shouldEqual resourceFor(userRW_groupX(orgTarget), 2, subject)
      acls.fetchAt(orgTarget, 1).accepted shouldEqual resourceFor(userR_groupX(orgTarget), 1, subject)
    }

    "fetch an ACL at specific revision containing only caller identities" in {
      acls.fetchSelfAt(orgTarget, 1).accepted shouldEqual resourceFor(userR(orgTarget), 1, subject)
    }

    "fail fetching a non existing acl" in {
      val targetNotExist = Organization(Label.unsafe("other"))
      acls.fetch(targetNotExist).rejectedWith[AclNotFound]
    }

    "fail fetching a non existing acl at specific revision" in {
      val targetNotExist = Organization(Label.unsafe("other"))
      acls.fetchAt(targetNotExist, 1).rejectedWith[AclNotFound]
    }

    "list ACLs" in {
      acls.append(groupR(AclAddress.Root), 4).accepted
      acls.append(anonR(projectTarget), 0).accepted

      forAll(List(any, AnyProject(org, withAncestors = false))) { filter =>
        acls.list(filter).accepted shouldEqual AclCollection(resourceFor(anonR(projectTarget), 1, subject))
      }
    }

    "list ACLs containing only caller identities" in {
      acls.listSelf(anyOrg).accepted shouldEqual AclCollection(resourceFor(userRW(orgTarget), 2, subject))
    }

    "list ACLs containing ancestors" in {
      acls.append(userRW(org2Target), 0).accepted

      acls.list(anyWithAncestors).accepted shouldEqual
        AclCollection(
          resourceFor(groupR(AclAddress.Root), 5, subject),
          resourceFor(userRW_groupX(orgTarget), 2, subject),
          resourceFor(userRW(org2Target), 1, subject),
          resourceFor(anonR(projectTarget), 1, subject)
        )

      acls.list(AnyProject(org, withAncestors = true)).accepted shouldEqual
        AclCollection(
          resourceFor(groupR(AclAddress.Root), 5, subject),
          resourceFor(userRW_groupX(orgTarget), 2, subject),
          resourceFor(anonR(projectTarget), 1, subject)
        )

      acls.list(anyOrgWithAncestors).accepted shouldEqual
        AclCollection(
          resourceFor(groupR(AclAddress.Root), 5, subject),
          resourceFor(userRW_groupX(orgTarget), 2, subject),
          resourceFor(userRW(org2Target), 1, subject)
        )
    }

    "list ACLs containing ancestors and caller identities" in {
      acls.listSelf(anyOrgWithAncestors).accepted shouldEqual
        AclCollection(
          resourceFor(Acl(AclAddress.Root), 5, subject),
          resourceFor(userRW(orgTarget), 2, subject),
          resourceFor(userRW(org2Target), 1, subject)
        )
    }

    "fetch ACLs containing ancestors" in {
      acls.fetchWithAncestors(projectTarget).accepted shouldEqual
        AclCollection(
          resourceFor(groupR(AclAddress.Root), 5, subject),
          resourceFor(userRW_groupX(orgTarget), 2, subject),
          resourceFor(anonR(projectTarget), 1, subject)
        )
    }

    val allEvents = SSEUtils.extract(
      (AclAddress.Root, AclAppended, 1L),
      (AclAddress.Root, AclReplaced, 2L),
      (AclAddress.Root, AclSubtracted, 3L),
      (AclAddress.Root, AclDeleted, 4L),
      (orgTarget, AclReplaced, 5L),
      (orgTarget, AclAppended, 6L),
      (AclAddress.Root, AclAppended, 7L),
      (projectTarget, AclAppended, 8L),
      (org2Target, AclAppended, 9L)
    )

    "get the different events from start" in {
      val events = acls
        .events()
        .map { e => (e.value.address, e.valueClass, e.offset) }
        .take(9L)
        .compile
        .toList

      events.accepted shouldEqual allEvents
    }

    "get the different current events from start" in {
      val events = acls
        .currentEvents()
        .map { e => (e.value.address, e.valueClass, e.offset) }
        .compile
        .toList

      events.accepted shouldEqual allEvents
    }

    "get the different events from offset 2" in {
      val events = acls
        .events(Offset.at(2L))
        .map { e => (e.value.address, e.valueClass, e.offset) }
        .take(9L - 2L)
        .compile
        .toList

      events.accepted shouldEqual allEvents.drop(2)
    }

    "get the different current events from offset 2" in {
      val events = acls
        .currentEvents(Offset.at(2L))
        .map { e => (e.value.address, e.valueClass, e.offset) }
        .compile
        .toList

      events.accepted shouldEqual allEvents.drop(2)
    }

    "fail to fetch an ACL on nonexistent revision" in {
      acls.fetchAt(orgTarget, 10).rejected shouldEqual RevisionNotFound(10, 2)
    }

    "fail to append an ACL already appended" in {
      acls.append(userRW(org2Target), 1).rejectedWith[NothingToBeUpdated]
    }

    "fail to subtract an ACL with permissions that do not exist" in {
      acls.subtract(anonR(org2Target), 1).rejectedWith[NothingToBeUpdated]
    }

    "fail to replace an ACL containing empty permissions" in {
      val aclWithEmptyPerms = Acl(org2Target, user -> Set(r), group -> Set.empty)
      acls.replace(aclWithEmptyPerms, 1).rejectedWith[AclCannotContainEmptyPermissionCollection]
    }

    "fail to append an ACL containing empty permissions" in {
      val aclWithEmptyPerms = Acl(org2Target, user -> Set(r), group -> Set.empty)
      acls.append(aclWithEmptyPerms, 1).rejectedWith[AclCannotContainEmptyPermissionCollection]
    }

    "fail to subtract an ACL containing empty permissions" in {
      val aclWithEmptyPerms = Acl(org2Target, user -> Set(r), group -> Set.empty)
      acls.subtract(aclWithEmptyPerms, 1).rejectedWith[AclCannotContainEmptyPermissionCollection]
    }

    "fail to delete an ACL already deleted" in {
      acls.delete(org2Target, 1).accepted
      acls.delete(org2Target, 2).rejectedWith[AclIsEmpty]
    }

    "fail to subtract an ACL that does not exist" in {
      val targetNotExist = Organization(Label.unsafe("other"))
      acls.subtract(anonR(targetNotExist), 0).rejectedWith[AclNotFound]
    }

    "fail to delete an ACL that does not exist" in {
      val targetNotExist = Organization(Label.unsafe("other"))
      acls.delete(targetNotExist, 0).rejectedWith[AclNotFound]
    }

    "fail to replace an ACL containing invalid permissions" in {
      val aclWithInvalidPerms = Acl(org2Target, user -> Set(r), group -> Set(Permission.unsafe("invalid")))
      acls.replace(aclWithInvalidPerms, 2).rejectedWith[UnknownPermissions]
    }

    "fail to append an ACL containing invalid permissions" in {
      val aclWithInvalidPerms = Acl(org2Target, user -> Set(r), group -> Set(Permission.unsafe("invalid")))
      acls.append(aclWithInvalidPerms, 2).rejectedWith[UnknownPermissions]
    }

    "fail to subtract an ACL containing invalid permissions" in {
      val aclWithInvalidPerms = Acl(orgTarget, user -> Set(r), group -> Set(Permission.unsafe("invalid")))
      acls.subtract(aclWithInvalidPerms, 2).rejectedWith[UnknownPermissions]
    }

    "subtract an ACL correctly" in {
      acls.replace(userRW(AclAddress.Root), 5).accepted
      acls.subtract(userW(AclAddress.Root), 6).accepted
      acls.fetch(AclAddress.Root).accepted shouldEqual resourceFor(userR(AclAddress.Root), 7, subject)
    }

  }

}
