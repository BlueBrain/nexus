package ai.senscience.nexus.tests.iam

import ai.senscience.nexus.tests.Identity.Authenticated
import ai.senscience.nexus.tests.Optics.error
import ai.senscience.nexus.tests.iam.types.*
import ai.senscience.nexus.tests.{HttpClient, Identity}
import akka.http.scaladsl.model.{HttpResponse, StatusCodes}
import cats.effect.IO
import cats.effect.unsafe.implicits.*
import cats.implicits.*
import ch.epfl.bluebrain.nexus.akka.marshalling.CirceUnmarshalling
import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClasspathResourceLoader
import io.circe.Json
import org.scalatest.matchers.should.Matchers
import org.scalatest.{Assertion, OptionValues}

import scala.jdk.CollectionConverters.*

class AclDsl(cl: HttpClient) extends CirceUnmarshalling with OptionValues with Matchers {

  private val logger = Logger[this.type]
  private val loader = ClasspathResourceLoader()

  def fetch(path: String, identity: Identity, self: Boolean = true, ancestors: Boolean = false)(
      assertAcls: AclListing => Assertion
  ): IO[Assertion] = {
    path should not startWith "/acls"
    cl.get[AclListing](s"/acls$path?ancestors=$ancestors&self=$self", identity) { (acls, response) =>
      response.status shouldEqual StatusCodes.OK
      assertAcls(acls)
    }
  }

  def addPermission(path: String, target: Authenticated, permission: Permission): IO[Assertion] =
    addPermissions(path, target, Set(permission))

  def addPermissions(path: String, target: Authenticated, permissions: Set[Permission]): IO[Assertion] = {
    loader
      .jsonContentOf(
        "iam/add.json",
        "realm" -> target.realm.name,
        "sub"   -> target.name,
        "perms" -> permissions.asJava
      )
      .flatMap(addPermissions(path, _, target.name))
  }

  def addPermissionAnonymous(path: String, permission: Permission): IO[Assertion] =
    addPermissionsAnonymous(path, Set(permission))

  def addPermissionsAnonymous(path: String, permissions: Set[Permission]): IO[Assertion] = {
    loader
      .jsonContentOf(
        "iam/add_annon.json",
        "perms" -> permissions.asJava
      )
      .flatMap(addPermissions(path, _, "Anonymous"))
  }

  def addPermissions(path: String, payload: Json, targetName: String): IO[Assertion] = {

    def assertResponse(json: Json, response: HttpResponse) =
      response.status match {
        case StatusCodes.Created | StatusCodes.OK =>
          succeed
        case StatusCodes.BadRequest               =>
          val errorType = error.`@type`.getOption(json)
          errorType.value shouldBe "NothingToBeUpdated"
        case s                                    => fail(s"We were not expecting $s when setting acls on $path for $targetName")
      }

    logger.info(s"Addings permissions to $path for $targetName") >>
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
        }.unsafeRunSync()
      }
  }

  def cleanAcls(target: Authenticated): IO[Assertion] =
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
          for {
            payload <- loader.jsonContentOf(
                         "iam/subtract-permissions.json",
                         "realm" -> target.realm.name,
                         "sub"   -> target.name,
                         "perms" -> acl.acl.head.permissions.asJava
                       )
            result  <-
              cl.patch[Json](s"/acls${acl._path}?rev=${acl._rev}", payload, Identity.ServiceAccount) { (_, response) =>
                response.status shouldEqual StatusCodes.OK
              }
          } yield result
        }
        .map(_ => succeed)
        .unsafeRunSync()
    }

  def cleanAclsAnonymous: IO[Assertion] =
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
          for {
            payload <- loader.jsonContentOf(
                         "iam/subtract-permissions-anon.json",
                         "perms" -> acl.acl.head.permissions.asJava
                       )
            result  <-
              cl.patch[Json](s"/acls${acl._path}?rev=${acl._rev}", payload, Identity.ServiceAccount) { (_, response) =>
                response.status shouldEqual StatusCodes.OK
              }
          } yield result
        }
        .map(_ => succeed)
        .unsafeRunSync()
    }

  def deletePermission(path: String, target: Authenticated, permission: Permission): IO[Assertion] =
    deletePermissions(path, target, Set(permission))

  def deletePermissions(path: String, target: Authenticated, permissions: Set[Permission]): IO[Assertion] =
    fetch(path, Identity.ServiceAccount) { acls =>
      deletePermissions(
        path,
        target,
        acls._results.head._rev,
        permissions
      ).unsafeRunSync()
    }

  def deletePermission(path: String, target: Authenticated, rev: Int, permission: Permission): IO[Assertion] = {
    deletePermissions(path, target, rev, Set(permission))
  }

  def deletePermissions(
      path: String,
      target: Authenticated,
      rev: Int,
      permissions: Set[Permission]
  ): IO[Assertion] = {
    for {
      body   <- loader.jsonContentOf(
                  "iam/subtract-permissions.json",
                  "realm" -> target.realm.name,
                  "sub"   -> target.name,
                  "perms" -> permissions.asJava
                )
      result <- cl.patch[Json](s"/acls$path?rev=$rev", body, Identity.ServiceAccount) { (_, response) =>
                  response.status shouldEqual StatusCodes.OK
                }
    } yield result
  }

  def checkAdminAcls(path: String, authenticated: Authenticated): IO[Assertion] = {
    logger.info(s"Gettings acls for $path using ${authenticated.name}") >>
      fetch(path, authenticated) { acls =>
        val acl   = acls._results.headOption.value
        val entry = acl.acl.headOption.value
        entry.permissions shouldEqual Permission.adminPermissions
      }
  }

}
