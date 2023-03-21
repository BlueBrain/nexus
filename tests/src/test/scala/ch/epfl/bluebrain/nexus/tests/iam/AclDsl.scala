package ch.epfl.bluebrain.nexus.tests.iam

import akka.http.scaladsl.model.{HttpResponse, StatusCodes}
import cats.implicits._
import ch.epfl.bluebrain.nexus.testkit.TestHelpers
import ch.epfl.bluebrain.nexus.tests.Identity.Authenticated
import ch.epfl.bluebrain.nexus.tests.Optics.error
import ch.epfl.bluebrain.nexus.tests.iam.types.{AclEntry, AclListing, Anonymous, Permission, User}
import ch.epfl.bluebrain.nexus.tests.{CirceUnmarshalling, HttpClient, Identity}
import com.typesafe.scalalogging.Logger
import io.circe.Json
import monix.bio.Task
import monix.execution.Scheduler.Implicits.global
import org.scalatest.matchers.should.Matchers
import org.scalatest.{Assertion, OptionValues}
import scala.jdk.CollectionConverters._

class AclDsl(cl: HttpClient) extends TestHelpers with CirceUnmarshalling with OptionValues with Matchers {

  private val logger = Logger[this.type]

  def fetch(path: String, identity: Identity, self: Boolean = true, ancestors: Boolean = false)(
      assertAcls: AclListing => Assertion
  ): Task[Assertion] = {
    path should not startWith "/acls"
    cl.get[AclListing](s"/acls$path?ancestors=$ancestors&self=$self", identity) { (acls, response) =>
      response.status shouldEqual StatusCodes.OK
      assertAcls(acls)
    }
  }

  def addPermission(path: String, target: Authenticated, permission: Permission): Task[Assertion] =
    addPermissions(path, target, Set(permission))

  def addPermissions(path: String, target: Authenticated, permissions: Set[Permission]): Task[Assertion] = {
    val json = jsonContentOf(
      "/iam/add.json",
      "realm" -> target.realm.name,
      "sub"   -> target.name,
      "perms" -> permissions.asJava
    )

    addPermissions(path, json, target.name)
  }

  def addPermissionAnonymous(path: String, permission: Permission): Task[Assertion] =
    addPermissionsAnonymous(path, Set(permission))

  def addPermissionsAnonymous(path: String, permissions: Set[Permission]): Task[Assertion] = {
    val json = jsonContentOf(
      "/iam/add_annon.json",
      "perms" -> permissions.asJava
    )

    addPermissions(path, json, "Anonymous")
  }

  def addPermissions(path: String, payload: Json, targetName: String): Task[Assertion] = {
    logger.info(s"Addings permissions to $path for $targetName")

    def assertResponse(json: Json, response: HttpResponse) =
      response.status match {
        case StatusCodes.Created | StatusCodes.OK =>
          logger.info(s"Permissions has been successfully added for $targetName on $path")
          succeed
        case StatusCodes.BadRequest               =>
          val errorType = error.`@type`.getOption(json)
          logger.warn(
            s"We got a bad request when adding permissions for $targetName on $path with error type $errorType"
          )
          errorType.value shouldBe "NothingToBeUpdated"
        case s                                    => fail(s"We were not expecting $s when setting acls on $path for $targetName")
      }

    fetch(path, Identity.ServiceAccount) { acls =>
      {
        val rev = acls._results.headOption
        rev match {
          case Some(r) =>
            cl.patch[Json](s"/acls$path?rev=${r._rev}", payload, Identity.ServiceAccount) {
              assertResponse
            }
          case None    =>
            cl.patch[Json](s"/acls$path", payload, Identity.ServiceAccount) {
              assertResponse
            }
        }
      }.runSyncUnsafe()
    }
  }

  def cleanAcls(target: Authenticated): Task[Assertion] =
    fetch(s"/*/*", Identity.ServiceAccount, ancestors = true, self = false) { acls =>
      val permissions = acls._results
        .map { acls =>
          val userAcls = acls.acl.filter {
            case AclEntry(User(_, name), _) if name == target.name => true
            case _                                                 => false
          }
          acls.copy(acl = userAcls)
        }
        .filter(_.acl.nonEmpty)

      permissions
        .parTraverse { acl =>
          val payload = jsonContentOf(
            "/iam/subtract-permissions.json",
            "realm" -> target.realm.name,
            "sub"   -> target.name,
            "perms" -> acl.acl.head.permissions.asJava
          )
          cl.patch[Json](s"/acls${acl._path}?rev=${acl._rev}", payload, Identity.ServiceAccount) { (_, response) =>
            response.status shouldEqual StatusCodes.OK
          }
        }
        .map(_ => succeed)
        .runSyncUnsafe()
    }

  def cleanAclsAnonymous: Task[Assertion] =
    fetch(s"/*/*", Identity.ServiceAccount, ancestors = true, self = false) { acls =>
      val permissions = acls._results
        .map { acls =>
          val userAcls = acls.acl.filter {
            case AclEntry(Anonymous, _) => true
            case _                      => false
          }
          acls.copy(acl = userAcls)
        }
        .filter(_.acl.nonEmpty)

      permissions
        .parTraverse { acl =>
          val payload = jsonContentOf(
            "/iam/subtract-permissions-anon.json",
            "perms" -> acl.acl.head.permissions.asJava
          )
          cl.patch[Json](s"/acls${acl._path}?rev=${acl._rev}", payload, Identity.ServiceAccount) { (_, response) =>
            response.status shouldEqual StatusCodes.OK
          }
        }
        .map(_ => succeed)
        .runSyncUnsafe()
    }

  def deletePermission(path: String, target: Authenticated, permission: Permission): Task[Assertion] =
    deletePermissions(path, target, Set(permission))

  def deletePermissions(path: String, target: Authenticated, permissions: Set[Permission]): Task[Assertion] =
    fetch(path, Identity.ServiceAccount) { acls =>
      deletePermissions(
        path,
        target,
        acls._results.head._rev,
        permissions
      ).runSyncUnsafe()
    }

  def deletePermission(path: String, target: Authenticated, rev: Int, permission: Permission): Task[Assertion] = {
    deletePermissions(path, target, rev, Set(permission))
  }

  def deletePermissions(
      path: String,
      target: Authenticated,
      rev: Int,
      permissions: Set[Permission]
  ): Task[Assertion] = {
    val body = jsonContentOf(
      "/iam/subtract-permissions.json",
      "realm" -> target.realm.name,
      "sub"   -> target.name,
      "perms" -> permissions.asJava
    )
    cl.patch[Json](s"/acls$path?rev=$rev", body, Identity.ServiceAccount) { (_, response) =>
      response.status shouldEqual StatusCodes.OK
    }
  }

  def checkAdminAcls(path: String, authenticated: Authenticated): Task[Assertion] = {
    logger.info(s"Gettings acls for $path using ${authenticated.name}")
    fetch(path, authenticated) { acls =>
      val acl   = acls._results.headOption.value
      val entry = acl.acl.headOption.value
      entry.permissions shouldEqual Permission.adminPermissions
    }
  }

}
