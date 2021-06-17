package ch.epfl.bluebrain.nexus.delta.service.projects

import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.rdf.syntax.iriStringContextSyntax
import ch.epfl.bluebrain.nexus.delta.sdk.Permissions.resources
import ch.epfl.bluebrain.nexus.delta.sdk.generators.PermissionsGen
import ch.epfl.bluebrain.nexus.delta.sdk.generators.PermissionsGen.ownerPermissions
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.{Acl, AclAddress}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.{Identity, ServiceAccount}
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectsConfig.AutomaticProvisioningConfig
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects._
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Label}
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.{AclSetup, OrganizationsDummy, OwnerPermissionsDummy, ProjectsDummy}
import ch.epfl.bluebrain.nexus.testkit.{IOFixedClock, IOValues}
import monix.execution.Scheduler
import org.scalatest.OptionValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import java.time.Instant
import java.util.UUID

class ProjectProvisioningSpec extends AnyWordSpecLike with Matchers with IOValues with IOFixedClock with OptionValues {

  val epoch: Instant                 = Instant.EPOCH
  implicit val subject: Subject      = Identity.User("user", Label.unsafe("realm"))
  val serviceAccount: ServiceAccount = ServiceAccount(Identity.User("serviceAccount", Label.unsafe("realm")))

  implicit val scheduler: Scheduler = Scheduler.global
  implicit val baseUri: BaseUri     = BaseUri("http://localhost", Label.unsafe("v1"))

  val uuid                  = UUID.randomUUID()
  implicit val uuidF: UUIDF = UUIDF.fixed(uuid)

  val orgUuid = UUID.randomUUID()

  val usersOrg = Label.unsafe("users-org")

  val rootPermissions = PermissionsGen.ownerPermissions
  val acls            = AclSetup
    .init(
      (subject, AclAddress.Root, rootPermissions)
    )
    .accepted

  lazy val organizations: OrganizationsDummy = {
    val orgUuidF: UUIDF = UUIDF.fixed(orgUuid)
    val orgs            = for {
      o <- OrganizationsDummy()(orgUuidF, ioClock)
      _ <- o.create(usersOrg, None)
    } yield o
    orgs.hideErrorsWith(r => new IllegalStateException(r.reason))
  }.accepted

  val provisioningConfig = AutomaticProvisioningConfig(
    enabled = true,
    permissions = Set(resources.read, resources.write),
    enabledRealms = Map(Label.unsafe("realm") -> Label.unsafe("users-org")),
    ProjectFields(
      Some("Auto provisioned project"),
      ApiMappings.empty,
      Some(PrefixIri.unsafe(iri"http://example.com/base/")),
      Some(PrefixIri.unsafe(iri"http://example.com/vocab/"))
    )
  )

  val projects = ProjectsDummy(
    organizations,
    Set(OwnerPermissionsDummy(acls, ownerPermissions, serviceAccount)),
    ApiMappings.empty
  ).accepted

  val provisioning = ProjectProvisioning(acls, projects, provisioningConfig)
  "Provisioning projects" should {

    "provision project with correct permissions" in {
      val subject: Subject = Identity.User("user1", Label.unsafe("realm"))
      val projectLabel     = Label.unsafe("user1")
      val projectRef       = ProjectRef(usersOrg, projectLabel)
      val acl              = Acl(AclAddress.Project(projectRef), subject -> provisioningConfig.permissions)
      provisioning(subject).accepted
      projects.fetchProject(projectRef).accepted shouldEqual Project(
        projectLabel,
        uuid,
        usersOrg,
        orgUuid,
        provisioningConfig.fields.description,
        provisioningConfig.fields.apiMappings,
        ProjectBase(provisioningConfig.fields.base.value.value),
        provisioningConfig.fields.vocab.value.value
      )
      acls.fetch(projectRef).accepted.value shouldEqual acl
    }
    "provision project with even if the ACLs have been set before" in {
      val subject: Subject = Identity.User("user2", Label.unsafe("realm"))
      val projectLabel     = Label.unsafe("user2")
      val projectRef       = ProjectRef(usersOrg, projectLabel)
      val acl              = Acl(AclAddress.Project(projectRef), subject -> provisioningConfig.permissions)
      acls.append(acl, 0L)(subject).accepted
      provisioning(subject).accepted
      projects.fetchProject(ProjectRef(usersOrg, projectLabel)).accepted shouldEqual Project(
        projectLabel,
        uuid,
        usersOrg,
        orgUuid,
        provisioningConfig.fields.description,
        provisioningConfig.fields.apiMappings,
        ProjectBase(provisioningConfig.fields.base.value.value),
        provisioningConfig.fields.vocab.value.value
      )
    }
  }
}
