package ch.epfl.bluebrain.nexus.delta.sdk.provisioning

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.rdf.syntax.iriStringContextSyntax
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclSimpleCheck
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.organizations.FetchActiveOrganization
import ch.epfl.bluebrain.nexus.delta.sdk.organizations.model.Organization
import ch.epfl.bluebrain.nexus.delta.sdk.organizations.model.OrganizationRejection.OrganizationNotFound
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.Permissions.resources
import ch.epfl.bluebrain.nexus.delta.sdk.projects.ProjectsImpl
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model._
import ch.epfl.bluebrain.nexus.delta.sdk.provisioning.ProjectProvisioning.InvalidProjectLabel
import ch.epfl.bluebrain.nexus.delta.sdk.{ConfigFixtures, ScopeInitializer}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Identity, Label, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.postgres.DoobieScalaTestFixture
import ch.epfl.bluebrain.nexus.testkit.scalatest.ce.{CatsEffectSpec, CatsIOValues}

import java.util.UUID

class ProjectProvisioningSpec extends CatsEffectSpec with DoobieScalaTestFixture with ConfigFixtures with CatsIOValues {

  implicit private val baseUri: BaseUri = BaseUri("http://localhost", Label.unsafe("v1"))

  private val uuid                  = UUID.randomUUID()
  implicit private val uuidF: UUIDF = UUIDF.fixed(uuid)

  private val orgUuid = UUID.randomUUID()

  private val usersOrg = Label.unsafe("users-org")

  private val aclCheck: AclSimpleCheck = AclSimpleCheck().accepted

  private def fetchOrg: FetchActiveOrganization = {
    case `usersOrg` => IO.pure(Organization(usersOrg, orgUuid, None))
    case other      => IO.raiseError(OrganizationNotFound(other))
  }

  private val provisioningConfig = AutomaticProvisioningConfig(
    enabled = true,
    permissions = Set(resources.read, resources.write),
    enabledRealms = Map(Label.unsafe("realm") -> Label.unsafe("users-org")),
    ProjectFields(
      Some("Auto provisioned project"),
      ApiMappings.empty,
      Some(PrefixIri.unsafe(iri"http://example.com/base/")),
      Some(PrefixIri.unsafe(iri"http://example.com/vocab/")),
      enforceSchema = true
    )
  )

  private lazy val projects = ProjectsImpl(
    fetchOrg,
    _ => IO.unit,
    ScopeInitializer.noop,
    ApiMappings.empty,
    eventLogConfig,
    xas,
    clock
  )

  private lazy val provisioning = ProjectProvisioning(aclCheck.append, projects, provisioningConfig)

  "Provisioning projects" should {

    "provision project with correct permissions" in {
      val subject: Subject = Identity.User("user1######", Label.unsafe("realm"))
      val projectLabel     = Label.unsafe("user1")
      val projectRef       = ProjectRef(usersOrg, projectLabel)
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
        enforceSchema = provisioningConfig.fields.enforceSchema,
        markedForDeletion = false
      )

      provisioningConfig.permissions.foreach { permission =>
        aclCheck.authorizeFor(AclAddress.Project(projectRef), permission, Set(subject)).accepted shouldEqual true
      }
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
        enforceSchema = provisioningConfig.fields.enforceSchema,
        markedForDeletion = false
      )
    }

    "fail to provision if it's not possible to sanitize username" in {
      val subject: Subject = Identity.User("!!!!!!!######", Label.unsafe("realm"))
      provisioning(subject).rejected shouldBe a[InvalidProjectLabel]
    }
  }
}
