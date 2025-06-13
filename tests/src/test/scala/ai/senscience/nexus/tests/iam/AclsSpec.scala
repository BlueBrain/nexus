package ai.senscience.nexus.tests.iam

import ai.senscience.nexus.tests.Identity.acls.Marge
import ai.senscience.nexus.tests.Identity.testRealm
import ai.senscience.nexus.tests.iam.types.{AclEntry, AclListing, Permission, User}
import ai.senscience.nexus.tests.{BaseIntegrationSpec, Identity}
import cats.implicits.*

class AclsSpec extends BaseIntegrationSpec {

  "manage acls" should {
    val orgPath1     = genString()
    val orgPath2     = genString()
    val orgs         = orgPath1 :: orgPath2 :: Nil
    val projectPath1 = genString()
    val projectPath2 = genString()
    val projects     = projectPath1 :: projectPath2 :: Nil

    val crossProduct =
      for {
        org     <- orgs
        project <- projects
      } yield {
        (org, project)
      }

    val defaultPermissions    = Permission.Projects.list.toSet
    val restrictedPermissions = defaultPermissions.filterNot(_ == Permission.Projects.Write)

    "add permissions for user on /" in {
      aclDsl.addPermissions("/", Marge, defaultPermissions)
    }

    "fetch permissions for user" in {
      aclDsl.fetch("/", Identity.ServiceAccount, self = false) { acls =>
        acls._results.head.acl
          .find {
            case AclEntry(User(_, Marge.name), _) => true
            case _                                => false
          }
          .value
          .permissions shouldEqual defaultPermissions
      }
    }

    "delete some permissions for user" in
      aclDsl.deletePermission(
        "/",
        Marge,
        Permission.Projects.Write
      )

    "check if permissions were removed" in {
      aclDsl.fetch("/", Identity.ServiceAccount, self = false) { acls =>
        acls._results.head.acl
          .find {
            case AclEntry(User(testRealm.name, Marge.name), _) => true
            case _                                             => false
          }
          .value
          .permissions shouldEqual restrictedPermissions
      }
    }

    "add permissions for user on paths with depth1" in {
      orgs.parTraverse { org =>
        aclDsl.addPermissions(
          s"/$org",
          Marge,
          defaultPermissions
        )
      }
    }

    "add permissions for user on /orgpath/projectpath1 and /orgpath/projectpath2" in {
      crossProduct.parTraverse { case (org, project) =>
        aclDsl.addPermissions(
          s"/$org/$project",
          Marge,
          defaultPermissions
        )
      }
    }

    def assertPermissions(acls: AclListing, org: String, project: String, expectedPermissions: Set[Permission]) =
      acls._results
        .find(_._path == s"/$org/$project")
        .value
        .acl
        .head
        .permissions shouldEqual expectedPermissions

    "list permissions on /*/*" in {
      aclDsl.fetch("/*/*", Marge) { acls =>
        crossProduct.foreach { case (org, project) =>
          assertPermissions(acls, org, project, defaultPermissions)
        }
        succeed
      }
    }

    "list permissions on /orgpath1/*" in {
      aclDsl.fetch(s"/$orgPath1/*", Marge) { acls =>
        acls._total shouldEqual 2
        projects.foreach { project =>
          assertPermissions(acls, orgPath1, project, defaultPermissions)
        }
        succeed
      }
    }

    "list permissions on /*/* with ancestors" in {
      aclDsl.fetch(s"/*/*", Marge, ancestors = true) { acls =>
        acls._results
          .find(_._path == "/")
          .value
          .acl
          .find {
            case AclEntry(User(testRealm.name, Marge.name), _) => true
            case _                                             => false
          }
          .value
          .permissions shouldEqual restrictedPermissions

        orgs.foreach { org =>
          acls._results
            .find(_._path == s"/$org")
            .value
            .acl
            .head
            .permissions shouldEqual defaultPermissions
        }

        crossProduct.foreach { case (org, project) =>
          assertPermissions(acls, org, project, defaultPermissions)
        }

        succeed
      }
    }
  }
}
