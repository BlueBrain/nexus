package ch.epfl.bluebrain.nexus.delta.service.utils

import ch.epfl.bluebrain.nexus.delta.sdk.generators.{OrganizationGen, PermissionsGen, ProjectGen}
import ch.epfl.bluebrain.nexus.delta.sdk.model.Label
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.{Acl, AclAddress}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.User
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.{Caller, ServiceAccount}
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.{AclsDummy, PermissionsDummy, RealmSetup}
import ch.epfl.bluebrain.nexus.delta.sdk.{Acls, Permissions}
import ch.epfl.bluebrain.nexus.testkit.{IOFixedClock, IOValues, TestHelpers}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class OwnerPermissionsScopeInitializationSpec
    extends AnyWordSpecLike
    with Matchers
    with IOValues
    with IOFixedClock
    with TestHelpers {

  private val saRealm: Label    = Label.unsafe("service-accounts")
  private val usersRealm: Label = Label.unsafe("users")

  private val acls: Acls = {
    val aclsIO = for {
      p <- PermissionsDummy(PermissionsGen.minimum)
      r <- RealmSetup.init(saRealm, usersRealm)
      a <- AclsDummy(p, r)
    } yield a
    aclsIO.accepted
  }

  private val sa: ServiceAccount = ServiceAccount(User("nexus-sa", saRealm))
  private val bob: Caller        = Caller.unsafe(User("bob", usersRealm))

  "An OwnerPermissionsScopeInitialization" should {
    val init = new OwnerPermissionsScopeInitialization(acls, PermissionsGen.ownerPermissions, sa)

    "set the owner permissions for a newly created organization" in {
      val organization = OrganizationGen.organization(genString())
      init.onOrganizationCreation(organization, bob.subject).accepted
      val resource     = acls.fetch(organization.label).accepted
      resource.value.value shouldEqual Map(bob.subject -> PermissionsGen.ownerPermissions)
      resource.rev shouldEqual 1L
      resource.createdBy shouldEqual sa.caller.subject
    }

    "not set owner permissions if acls are already defined for an org" in {
      val organization = OrganizationGen.organization(genString())
      acls
        .append(Acl(organization.label, bob.subject -> Set(Permissions.resources.read)), 0L)(
          sa.caller.subject
        )
        .accepted
      init.onOrganizationCreation(organization, bob.subject).accepted
      val resource     = acls.fetch(organization.label).accepted
      resource.value.value shouldEqual Map(bob.subject -> Set(Permissions.resources.read))
      resource.rev shouldEqual 1L
      resource.createdBy shouldEqual sa.caller.subject
    }

    "set the owner permissions for a newly created project" in {
      val project  = ProjectGen.project(genString(), genString())
      init.onProjectCreation(project, bob.subject).accepted
      val resource = acls.fetch(AclAddress.Project(project.ref)).accepted
      resource.value.value shouldEqual Map(bob.subject -> PermissionsGen.ownerPermissions)
      resource.rev shouldEqual 1L
      resource.createdBy shouldEqual sa.caller.subject
    }

    "not set owner permissions if acls are already defined for a project" in {
      val project  = ProjectGen.project(genString(), genString())
      acls
        .append(Acl(AclAddress.Project(project.ref), bob.subject -> Set(Permissions.resources.read)), 0L)(
          sa.caller.subject
        )
        .accepted
      init.onProjectCreation(project, bob.subject).accepted
      val resource = acls.fetch(AclAddress.Project(project.ref)).accepted
      resource.value.value shouldEqual Map(bob.subject -> Set(Permissions.resources.read))
      resource.rev shouldEqual 1L
      resource.createdBy shouldEqual sa.caller.subject
    }
  }

}
