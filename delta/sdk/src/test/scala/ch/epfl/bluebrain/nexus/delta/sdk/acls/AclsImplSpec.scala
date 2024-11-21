package ch.epfl.bluebrain.nexus.delta.sdk.acls

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.sdk.ConfigFixtures
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.AclAddress.Organization
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.AclAddressFilter.{AnyOrganization, AnyOrganizationAnyProject, AnyProject}
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.AclRejection.{AclCannotContainEmptyPermissionCollection, AclIsEmpty, AclNotFound, NothingToBeUpdated, RevisionNotFound, UnknownPermissions}
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.{Acl, AclAddress, AclCollection, AclState}
import ch.epfl.bluebrain.nexus.delta.sdk.generators.AclGen.resourceFor
import ch.epfl.bluebrain.nexus.delta.sdk.generators.PermissionsGen
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.model.Permission
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Anonymous, Group, Subject}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Identity, Label, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.postgres.DoobieScalaTestFixture
import ch.epfl.bluebrain.nexus.testkit.scalatest.ce.CatsEffectSpec
import org.scalatest.CancelAfterFailure

import java.time.Instant

class AclsImplSpec extends CatsEffectSpec with DoobieScalaTestFixture with CancelAfterFailure with ConfigFixtures {

  val epoch: Instant            = Instant.EPOCH
  val realm: Label              = Label.unsafe("realm")
  val realm2: Label             = Label.unsafe("myrealm2")
  implicit val subject: Subject = Identity.User("user", realm)
  implicit val caller: Caller   = Caller.unsafe(subject)
  implicit val baseUri: BaseUri = BaseUri("http://localhost", Label.unsafe("v1"))

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
      IO.pure(minimumPermissions),
      Acls.findUnknownRealms(_, Set(realm, realm2)),
      minimumPermissions,
      eventLogConfig,
      xas,
      clock
    )

    "return the full permissions for Anonymous if no permissions are defined" in {
      val expected: AclCollection =
        AclCollection(AclState.initial(minimumPermissions).toResource)
      acls.fetchWithAncestors(projectTarget).accepted shouldEqual expected
      acls.fetchWithAncestors(orgTarget).accepted shouldEqual expected
      acls.fetchWithAncestors(AclAddress.Root).accepted shouldEqual expected
    }

    "return false on `/` as no acl is set yet" in {
      acls.isRootAclSet.accepted shouldEqual false
    }

    "append an ACL" in {
      acls.append(userR(AclAddress.Root), 0).accepted shouldEqual resourceFor(userR(AclAddress.Root), 1, subject)
    }

    "return true on `/` now that an acl has been appended" in {
      acls.isRootAclSet.accepted shouldEqual true
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

    s"should delete the entry for a project" in {
      val project      = ProjectRef.unsafe("org", "to_delete")
      acls.append(userR(project), 0).accepted
      val deletionTask = Acls.projectDeletionTask(acls)
      deletionTask(project).accepted
      acls.fetch(project).rejectedWith[AclNotFound]
    }

  }

}
