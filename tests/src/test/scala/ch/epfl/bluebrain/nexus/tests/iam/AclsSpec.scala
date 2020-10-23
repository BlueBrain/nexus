package ch.epfl.bluebrain.nexus.tests.iam

import akka.http.scaladsl.model.StatusCodes
import cats.implicits._
import ch.epfl.bluebrain.nexus.tests.Identity.UserCredentials
import ch.epfl.bluebrain.nexus.tests.Tags.AclsTag
import ch.epfl.bluebrain.nexus.tests.iam.types.{AclEntry, AclListing, Permission, User}
import ch.epfl.bluebrain.nexus.tests.{BaseSpec, Identity, Realm}
import monix.execution.Scheduler.Implicits.global

class AclsSpec extends BaseSpec {

  private val testRealm  = Realm("acls" + genString())
  private val testClient = Identity.ClientCredentials(genString(), genString(), testRealm)
  private val Marge      = UserCredentials(genString(), genString(), testRealm)

  override def beforeAll(): Unit = {
    super.beforeAll()
    initRealm(
      testRealm,
      Identity.ServiceAccount,
      testClient,
      Marge :: Nil
    ).runSyncUnsafe()
  }

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

    "add permissions for user on /" taggedAs AclsTag in {
      aclDsl.addPermissions("/", Marge, defaultPermissions)
    }

    "fetch permissions for user" taggedAs AclsTag in {
      deltaClient.get[AclListing]("/acls/?self=false", Identity.ServiceAccount) { (acls, result) =>
        result.status shouldEqual StatusCodes.OK
        acls._results.head.acl
          .find {
            case AclEntry(User(_, Marge.name), _) => true
            case _                                => false
          }
          .value
          .permissions shouldEqual defaultPermissions
      }
    }

    "delete some permissions for user" taggedAs AclsTag in
      aclDsl.deletePermission(
        "/",
        Marge,
        Permission.Projects.Write
      )

    "check if permissions were removed" taggedAs AclsTag in {
      deltaClient.get[AclListing]("/acls/?self=false", Identity.ServiceAccount) { (acls, response) =>
        response.status shouldEqual StatusCodes.OK
        acls._results.head.acl
          .find {
            case AclEntry(User(testRealm.name, Marge.name), _) => true
            case _                                             => false
          }
          .value
          .permissions shouldEqual restrictedPermissions
      }
    }

    "add permissions for user on paths with depth1" taggedAs AclsTag in {
      orgs.traverse { org =>
        aclDsl.addPermissions(
          s"/$org",
          Marge,
          defaultPermissions
        )
      }
    }

    "add permissions for user on /orgpath/projectpath1 and /orgpath/projectpath2" taggedAs AclsTag in {
      crossProduct.traverse { case (org, project) =>
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

    "list permissions on /*/*" taggedAs AclsTag in {
      deltaClient.get[AclListing]("/acls/*/*", Marge) { (acls, response) =>
        response.status shouldEqual StatusCodes.OK
        crossProduct.foreach { case (org, project) =>
          assertPermissions(acls, org, project, defaultPermissions)
        }
        succeed
      }
    }

    "list permissions on /orgpath1/*" taggedAs AclsTag in {
      deltaClient.get[AclListing](s"/acls/$orgPath1/*", Marge) { (acls, response) =>
        response.status shouldEqual StatusCodes.OK
        acls._total shouldEqual 2
        projects.foreach { project =>
          assertPermissions(acls, orgPath1, project, defaultPermissions)
        }
        succeed
      }
    }

    "list permissions on /*/* with ancestors" taggedAs AclsTag in {
      deltaClient.get[AclListing]("/acls/*/*?ancestors=true", Marge) { (acls, response) =>
        response.status shouldEqual StatusCodes.OK
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
