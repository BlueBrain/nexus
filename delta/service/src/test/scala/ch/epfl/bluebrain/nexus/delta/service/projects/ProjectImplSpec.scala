package ch.epfl.bluebrain.nexus.delta.service.projects

import ch.epfl.bluebrain.nexus.delta.sdk.Permissions._
import ch.epfl.bluebrain.nexus.delta.sdk.generators.PermissionsGen
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.{Acl, AclAddress}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{ProjectEvent, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Envelope, Label}
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.{AclsDummy, PermissionsDummy, ProjectsBehaviors}
import ch.epfl.bluebrain.nexus.delta.sdk.{Permissions, Projects}
import ch.epfl.bluebrain.nexus.delta.service.utils.EventLogUtils
import ch.epfl.bluebrain.nexus.delta.service.{AbstractDBSpec, ConfigFixtures}
import ch.epfl.bluebrain.nexus.sourcing.EventLog
import monix.bio.UIO
import org.scalatest.OptionValues

class ProjectImplSpec extends AbstractDBSpec with ProjectsBehaviors with OptionValues with ConfigFixtures {

  val serviceAccount: Subject        = Identity.User("serviceAccount", Label.unsafe("realm"))
  val projectsConfig: ProjectsConfig = ProjectsConfig(aggregate, keyValueStore, pagination, indexing)

  val ownerPermissions = Set(
    Permissions.projects.read,
    resources.read,
    resources.write,
    resolvers.write,
    views.write,
    views.query,
    schemas.write,
    files.write,
    storages.write
  )

  // A project created on org1 has all owner permissions on / and org1
  val (rootPermissions, org1Permissions) = ownerPermissions.splitAt(ownerPermissions.size / 2)
  val proj10                             = Label.unsafe("proj10")

  // A project created on org2 lacks some of the owner permissions
  // proj20 has all owner permissions on /, org2 and proj20
  val proj20                               = Label.unsafe("proj20")
  val (org2Permissions, proj20Permissions) = org1Permissions.splitAt(org1Permissions.size / 2)

  // proj21 has some extra acls that should be conserved
  val proj21            = Label.unsafe("proj21")
  val proj21Permissions = Set(orgs.read, orgs.create)

  // proj22 has no permission set
  val proj22 = Label.unsafe("proj22")

  val acls = {
    for {
      acls <- AclsDummy(PermissionsDummy(PermissionsGen.minimum))
      _    <- acls.append(AclAddress.Root, Acl(subject -> rootPermissions), 0L)(serviceAccount)
      _    <- acls.append(AclAddress.Organization(org1), Acl(subject -> org1Permissions), 0L)(serviceAccount)
      _    <- acls.append(AclAddress.Organization(org2), Acl(subject -> org2Permissions), 0L)(serviceAccount)
      _    <- acls.append(AclAddress.Project(org2, proj20), Acl(subject -> proj20Permissions), 0L)(serviceAccount)
      _    <- acls.append(AclAddress.Project(org2, proj21), Acl(subject -> proj21Permissions), 0L)(serviceAccount)
    } yield acls
  }.accepted

  override def create: UIO[Projects] =
    for {
      eventLog <- EventLog.postgresEventLog[Envelope[ProjectEvent]](EventLogUtils.toEnvelope).hideErrors
      orgs     <- organizations
      projects <- ProjectsImpl(projectsConfig, eventLog, orgs, acls, ownerPermissions, serviceAccount)
    } yield projects

  "Creating projects" should {

    "not set any permissions if all permissions has been set on / and the org" in {
      val proj10Ref = ProjectRef(org1, proj10)
      projects.create(proj10Ref, payload).accepted.id shouldEqual proj10Ref

      acls.fetch(AclAddress.Project(org1, proj10)).accepted shouldEqual None
    }

    "not set any permissions if all permissions has been set on /, the org and the project" in {
      val proj20Ref = ProjectRef(org2, proj20)
      projects.create(proj20Ref, payload).accepted.id shouldEqual proj20Ref

      acls.fetch(AclAddress.Project(org2, proj20)).accepted.map(_.value.value) shouldEqual Some(
        Map(subject -> proj20Permissions)
      )
    }

    "set owner permissions if not all permissions are present and keep other ones" in {
      val proj21Ref = ProjectRef(org2, proj21)
      projects.create(proj21Ref, payload).accepted.id shouldEqual proj21Ref

      acls.fetch(AclAddress.Project(org2, proj21)).accepted.map(_.value.value) shouldEqual Some(
        Map(subject -> (proj21Permissions ++ ownerPermissions))
      )
    }

    "set owner permissions if not all permissions are present" in {
      val proj22Ref = ProjectRef(org2, proj22)
      projects.create(proj22Ref, payload).accepted.id shouldEqual proj22Ref

      acls.fetch(AclAddress.Project(org2, proj22)).accepted.map(_.value.value) shouldEqual Some(
        Map(subject -> ownerPermissions)
      )
    }

  }
}
