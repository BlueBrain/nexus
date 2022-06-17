package ch.epfl.bluebrain.nexus.delta.sdk.projects

import ch.epfl.bluebrain.nexus.delta.sdk.ConfigFixtures
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.{Acl, AclAddress}
import ch.epfl.bluebrain.nexus.delta.sdk.acls.{Acls, AclsConfig, AclsImpl}
import ch.epfl.bluebrain.nexus.delta.sdk.generators.{OrganizationGen, PermissionsGen, ProjectGen}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.{Caller, ServiceAccount}
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.Permissions
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.User
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import ch.epfl.bluebrain.nexus.testkit.{DoobieFixture, IOFixedClock, IOValues, TestHelpers}
import monix.bio.UIO
import org.scalatest.matchers.should.Matchers

class OwnerPermissionsScopeInitializationSpec
    extends DoobieFixture
    with Matchers
    with IOValues
    with IOFixedClock
    with TestHelpers
    with ConfigFixtures {

  private val saRealm: Label    = Label.unsafe("service-accounts")
  private val usersRealm: Label = Label.unsafe("users")

  private lazy val acls: Acls =
    AclsImpl(
      UIO.pure(PermissionsGen.minimum),
      Acls.findUnknownRealms(_, Set(saRealm, usersRealm)),
      PermissionsGen.minimum,
      AclsConfig(eventLogConfig),
      xas
    )

  private val sa: ServiceAccount = ServiceAccount(User("nexus-sa", saRealm))
  private val bob: Caller        = Caller.unsafe(User("bob", usersRealm))

  "An OwnerPermissionsScopeInitialization" should {
    lazy val init = OwnerPermissionsScopeInitialization(acls, PermissionsGen.ownerPermissions, sa)

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
        .append(Acl(organization.label, bob.subject -> Set(Permissions.resources.read)), 0)(
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
        .append(Acl(AclAddress.Project(project.ref), bob.subject -> Set(Permissions.resources.read)), 0)(
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
