package ch.epfl.bluebrain.nexus.delta.sdk.testkit

import java.time.Instant

import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{nxv, schemas}
import ch.epfl.bluebrain.nexus.delta.sdk.{AclResource, Acls}
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceRef.Latest
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.AclAddress.Organization
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.AclAddressFilter.{AnyOrganization, AnyOrganizationAnyProject, AnyProject}
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.AclRejection.{AclCannotContainEmptyPermissionCollection, AclIsEmpty, AclNotFound, NothingToBeUpdated, RevisionNotFound, UnknownPermissions}
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.{Acl, AclAddress, AclCollection}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.{Anonymous, Group, Subject}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.{Caller, Identity}
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Label, ResourceF}
import ch.epfl.bluebrain.nexus.testkit.{CirceLiteral, IOFixedClock, IOValues, TestHelpers}
import monix.bio.Task
import monix.execution.Scheduler
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.{Inspectors, OptionValues}

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
    with Inspectors =>

  val epoch: Instant                = Instant.EPOCH
  implicit val subject: Subject     = Identity.User("user", Label.unsafe("realm"))
  implicit val caller: Caller       = Caller.unsafe(subject)
  implicit val scheduler: Scheduler = Scheduler.global

  val user: Identity  = subject
  val group: Identity = Group("mygroup", Label.unsafe("myrealm2"))
  val anon: Identity  = Anonymous

  val r: Permission        = Permission.unsafe("acls/read")
  val w: Permission        = Permission.unsafe("acls/write")
  val x: Permission        = Permission.unsafe("organizations/create")
  val rwx: Set[Permission] = Set(r, w, x)

  val userR         = Acl(user -> Set(r))
  val userW         = Acl(user -> Set(w))
  val userRW        = Acl(user -> Set(r, w))
  val userR_groupX  = Acl(user -> Set(r), group -> Set(x))
  val userRW_groupX = Acl(user -> Set(r, w), group -> Set(x))
  val groupR        = Acl(group -> Set(r))
  val groupX        = Acl(group -> Set(x))
  val anonR         = Acl(anon -> Set(r))

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

  def create: Task[Acls]

  def resourceFor(address: AclAddress, acl: Acl, rev: Long, deprecated: Boolean = false): AclResource =
    ResourceF(
      id = address,
      rev = rev,
      types = Set(nxv.AccessControlList),
      deprecated = deprecated,
      createdAt = Instant.EPOCH,
      createdBy = subject,
      updatedAt = Instant.EPOCH,
      updatedBy = subject,
      schema = Latest(schemas.acls),
      value = acl
    )

  "An ACLs implementation" should {
    val acls =
      create
        .timeoutWith(10.seconds, new TimeoutException("Unable to create a permissions instance"))
        .memoizeOnSuccess
        .accepted

    "append an ACL" in {
      acls.append(AclAddress.Root, userR, 0L).accepted shouldEqual resourceFor(AclAddress.Root, userR, 1L)
    }

    "replace an ACL" in {
      acls.replace(AclAddress.Root, userR_groupX, 1L).accepted shouldEqual resourceFor(
        AclAddress.Root,
        userR_groupX,
        2L
      )
    }

    "subtract an ACL" in {
      acls.subtract(AclAddress.Root, groupX, 2L).accepted shouldEqual resourceFor(AclAddress.Root, userR, 3L)
    }

    "delete an ACL" in {
      acls.delete(AclAddress.Root, 3L).accepted shouldEqual resourceFor(AclAddress.Root, Acl.empty, 4L)
    }

    "fetch an ACL" in {
      acls.replace(orgTarget, userR_groupX, 0L).accepted
      acls.fetch(orgTarget).accepted.value shouldEqual resourceFor(orgTarget, userR_groupX, 1L)
    }

    "fetch an ACL containing only caller identities" in {
      acls.fetchSelf(orgTarget).accepted.value shouldEqual resourceFor(orgTarget, userR, 1L)
    }

    "fetch an ACL data" in {
      acls.fetchAcl(orgTarget).accepted shouldEqual userR_groupX
      acls.fetchSelfAcl(orgTarget).accepted shouldEqual userR
      acls.fetchAcl(projectTarget).accepted shouldEqual Acl.empty
    }

    "fetch an ACL at specific revision" in {
      acls.append(orgTarget, userW, 1L).accepted
      acls.fetchAt(orgTarget, 2L).accepted.value shouldEqual resourceFor(orgTarget, userRW_groupX, 2L)
      acls.fetchAt(orgTarget, 1L).accepted.value shouldEqual resourceFor(orgTarget, userR_groupX, 1L)
    }

    "fetch an ACL at specific revision containing only caller identities" in {
      acls.fetchSelfAt(orgTarget, 1L).accepted.value shouldEqual resourceFor(orgTarget, userR, 1L)
    }

    "fetch a non existing acl" in {
      val targetNotExist = Organization(Label.unsafe("other"))
      acls.fetch(targetNotExist).accepted shouldEqual None
    }

    "fetch a non existing acl at specific revision" in {
      val targetNotExist = Organization(Label.unsafe("other"))
      acls.fetchAt(targetNotExist, 1L).accepted shouldEqual None
    }

    "list ACLs" in {
      acls.append(AclAddress.Root, groupR, 4L).accepted
      acls.append(projectTarget, anonR, 0L).accepted

      forAll(List(any, AnyProject(org, withAncestors = false))) { filter =>
        acls.list(filter).accepted shouldEqual
          AclCollection(resourceFor(projectTarget, anonR, 1L))
      }
    }

    "list ACLs containing only caller identities" in {
      acls.listSelf(anyOrg).accepted shouldEqual AclCollection(resourceFor(orgTarget, userRW, 2L))
    }

    "list ACLs containing ancestors" in {
      acls.append(org2Target, userRW, 0L).accepted

      acls.list(anyWithAncestors).accepted shouldEqual
        AclCollection(
          resourceFor(AclAddress.Root, groupR, 5L),
          resourceFor(orgTarget, userRW_groupX, 2L),
          resourceFor(org2Target, userRW, 1L),
          resourceFor(projectTarget, anonR, 1L)
        )

      acls.list(AnyProject(org, withAncestors = true)).accepted shouldEqual
        AclCollection(
          resourceFor(AclAddress.Root, groupR, 5L),
          resourceFor(orgTarget, userRW_groupX, 2L),
          resourceFor(projectTarget, anonR, 1L)
        )

      acls.list(anyOrgWithAncestors).accepted shouldEqual
        AclCollection(
          resourceFor(AclAddress.Root, groupR, 5L),
          resourceFor(orgTarget, userRW_groupX, 2L),
          resourceFor(org2Target, userRW, 1L)
        )
    }

    "list ACLs containing ancestors and caller identities" in {
      acls.listSelf(anyOrgWithAncestors).accepted shouldEqual
        AclCollection(
          resourceFor(AclAddress.Root, Acl.empty, 5L),
          resourceFor(orgTarget, userRW, 2L),
          resourceFor(org2Target, userRW, 1L)
        )
    }

    "fetch ACLs containing ancestors" in {
      acls.fetchWithAncestors(projectTarget).accepted shouldEqual
        AclCollection(
          resourceFor(AclAddress.Root, groupR, 5L),
          resourceFor(orgTarget, userRW_groupX, 2L),
          resourceFor(projectTarget, anonR, 1L)
        )
    }

    "fetch ACLs containing ancestors at specific revision" in {
      acls.fetchAtWithAncestors(projectTarget, 1L).accepted shouldEqual
        AclCollection(
          resourceFor(AclAddress.Root, userR, 1L),
          resourceFor(orgTarget, userR_groupX, 1L),
          resourceFor(projectTarget, anonR, 1L)
        )
    }

    "fail to fetch an ACL on nonexistent revision" in {
      acls.fetchAt(orgTarget, 10L).rejected shouldEqual RevisionNotFound(10L, 2L)
    }

    "fail to append an ACL already appended" in {
      acls.append(org2Target, userRW, 1L).rejectedWith[NothingToBeUpdated]
    }

    "fail to subtract an ACL with permissions that do not exist" in {
      acls.subtract(org2Target, anonR, 1L).rejectedWith[NothingToBeUpdated]
    }

    "fail to replace an ACL containing empty permissions" in {
      val aclWithEmptyPerms = Acl(user -> Set(r), group -> Set.empty)
      acls.replace(org2Target, aclWithEmptyPerms, 1L).rejectedWith[AclCannotContainEmptyPermissionCollection]
    }

    "fail to append an ACL containing empty permissions" in {
      val aclWithEmptyPerms = Acl(user -> Set(r), group -> Set.empty)
      acls.append(org2Target, aclWithEmptyPerms, 1L).rejectedWith[AclCannotContainEmptyPermissionCollection]
    }

    "fail to subtract an ACL containing empty permissions" in {
      val aclWithEmptyPerms = Acl(user -> Set(r), group -> Set.empty)
      acls.subtract(org2Target, aclWithEmptyPerms, 1L).rejectedWith[AclCannotContainEmptyPermissionCollection]
    }

    "fail to delete an ACL already deleted" in {
      acls.delete(org2Target, 1L).accepted
      acls.delete(org2Target, 2L).rejectedWith[AclIsEmpty]
    }

    "fail to subtract an ACL that does not exist" in {
      val targetNotExist = Organization(Label.unsafe("other"))
      acls.subtract(targetNotExist, anonR, 0L).rejectedWith[AclNotFound]
    }

    "fail to delete an ACL that does not exist" in {
      val targetNotExist = Organization(Label.unsafe("other"))
      acls.delete(targetNotExist, 0L).rejectedWith[AclNotFound]
    }

    "fail to replace an ACL containing invalid permissions" in {
      val aclWithInvalidPerms = Acl(user -> Set(r), group -> Set(Permission.unsafe("invalid")))
      acls.replace(org2Target, aclWithInvalidPerms, 2L).rejectedWith[UnknownPermissions]
    }

    "fail to append an ACL containing invalid permissions" in {
      val aclWithInvalidPerms = Acl(user -> Set(r), group -> Set(Permission.unsafe("invalid")))
      acls.append(org2Target, aclWithInvalidPerms, 2L).rejectedWith[UnknownPermissions]
    }

    "fail to subtract an ACL containing invalid permissions" in {
      val aclWithInvalidPerms = Acl(user -> Set(r), group -> Set(Permission.unsafe("invalid")))
      acls.subtract(orgTarget, aclWithInvalidPerms, 2L).rejectedWith[UnknownPermissions]
    }
  }

}
