package ch.epfl.bluebrain.nexus.delta.sdk.provisioning

import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.rdf.syntax.iriStringContextSyntax
import ch.epfl.bluebrain.nexus.delta.sdk.ConfigFixtures
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclSimpleCheck
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.{Acl, AclAddress}
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.organizations.model.Organization
import ch.epfl.bluebrain.nexus.delta.sdk.organizations.model.OrganizationRejection.OrganizationNotFound
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.Permissions.resources
import ch.epfl.bluebrain.nexus.delta.sdk.projects.Projects.FetchOrganization
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ProjectRejection.WrappedOrganizationRejection
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model._
import ch.epfl.bluebrain.nexus.delta.sdk.projects.{ProjectsConfig, ProjectsImpl}
import ch.epfl.bluebrain.nexus.delta.sdk.provisioning.ProjectProvisioning.InvalidProjectLabel
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Identity, Label, ProjectRef}
import ch.epfl.bluebrain.nexus.testkit.{DoobieScalaTestFixture, IOFixedClock, IOValues}
import monix.bio.{IO, UIO}
import org.scalatest.OptionValues
import org.scalatest.matchers.should.Matchers

import java.util.UUID

class ProjectProvisioningSpec
    extends DoobieScalaTestFixture
    with Matchers
    with IOValues
    with IOFixedClock
    with OptionValues
    with ConfigFixtures {

  implicit private val baseUri: BaseUri = BaseUri("http://localhost", Label.unsafe("v1"))

  private val uuid                  = UUID.randomUUID()
  implicit private val uuidF: UUIDF = UUIDF.fixed(uuid)

  private val orgUuid = UUID.randomUUID()

  private val usersOrg = Label.unsafe("users-org")

  private val aclCheck: AclSimpleCheck = AclSimpleCheck().accepted

  private def fetchOrg: FetchOrganization = {
    case `usersOrg` => UIO.pure(Organization(usersOrg, orgUuid, None))
    case other      => IO.raiseError(WrappedOrganizationRejection(OrganizationNotFound(other)))
  }

  private val provisioningConfig = AutomaticProvisioningConfig(
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

  private val config = ProjectsConfig(eventLogConfig, pagination, cacheConfig)

  private lazy val projects = ProjectsImpl(
    fetchOrg,
    Set.empty,
    ApiMappings.empty,
    config,
    xas
  ).accepted

  private lazy val provisioning = ProjectProvisioning(aclCheck.append, projects, provisioningConfig)

  "Provisioning projects" should {

    "provision project with correct permissions" in {
      val subject: Subject = Identity.User("user1######", Label.unsafe("realm"))
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
        projects.defaultApiMappings,
        ProjectBase(provisioningConfig.fields.base.value.value),
        provisioningConfig.fields.vocab.value.value,
        markedForDeletion = false
      )
      aclCheck.fetchOne(projectRef).accepted shouldEqual acl
    }

    "provision project with even if the ACLs have been set before" in {
      val subject: Subject = Identity.User("user2", Label.unsafe("realm"))
      val projectLabel     = Label.unsafe("user2")
      val projectRef       = ProjectRef(usersOrg, projectLabel)
      aclCheck.append(AclAddress.Project(projectRef), subject -> provisioningConfig.permissions).accepted
      provisioning(subject).accepted
      projects.fetchProject(ProjectRef(usersOrg, projectLabel)).accepted shouldEqual Project(
        projectLabel,
        uuid,
        usersOrg,
        orgUuid,
        provisioningConfig.fields.description,
        provisioningConfig.fields.apiMappings,
        projects.defaultApiMappings,
        ProjectBase(provisioningConfig.fields.base.value.value),
        provisioningConfig.fields.vocab.value.value,
        markedForDeletion = false
      )
    }

    "fail to provision if it's not possible to sanitize username" in {
      val subject: Subject = Identity.User("!!!!!!!######", Label.unsafe("realm"))
      provisioning(subject).rejected shouldBe a[InvalidProjectLabel]
    }
  }
}
