package ch.epfl.bluebrain.nexus.tests.iam

import akka.http.scaladsl.model.StatusCodes
import ch.epfl.bluebrain.nexus.tests.iam.types.{Permission, Permissions}
import ch.epfl.bluebrain.nexus.tests.{BaseSpec, Identity}
import io.circe.Json
import monix.bio.Task

class PermissionsSpec extends BaseSpec {

  "manage permissions" should {
    val permission1 = Permission(genString(8), genString(8))
    val permission2 = Permission(genString(8), genString(8))

    "clear permissions" in {
      deltaClient.get[Permissions]("/permissions", Identity.ServiceAccount) { (permissions, response) =>
        runTask {
          response.status shouldEqual StatusCodes.OK
          if (permissions.permissions == Permission.minimalPermissions)
            Task(succeed)
          else
            deltaClient.delete[Json](s"/permissions?rev=${permissions._rev}", Identity.ServiceAccount) {
              (_, response) =>
                response.status shouldEqual StatusCodes.OK
            }
        }
      }
    }

    "add permissions" in {
      permissionDsl.addPermissions(
        permission1,
        permission2
      )
    }

    "check added permissions" in {
      deltaClient.get[Permissions]("/permissions", Identity.ServiceAccount) { (permissions, response) =>
        response.status shouldEqual StatusCodes.OK
        permissions.permissions shouldEqual Permission.minimalPermissions + permission1 + permission2
      }
    }

    "subtract permissions" in {
      deltaClient.get[Permissions]("/permissions", Identity.ServiceAccount) { (permissions, response) =>
        runTask {
          response.status shouldEqual StatusCodes.OK
          val body = jsonContentOf(
            "/iam/permissions/subtract.json",
            permissionDsl.permissionsRepl(permission2 :: Nil)
          )
          deltaClient.patch[Json](s"/permissions?rev=${permissions._rev}", body, Identity.ServiceAccount) {
            (_, response) =>
              response.status shouldEqual StatusCodes.OK
          }
        }
      }
    }

    "check subtracted permissions" in {
      deltaClient.get[Permissions]("/permissions", Identity.ServiceAccount) { (permissions, response) =>
        response.status shouldEqual StatusCodes.OK
        permissions.permissions shouldEqual Permission.minimalPermissions + permission1
      }
    }

    "replace permissions" in {
      deltaClient.get[Permissions]("/permissions", Identity.ServiceAccount) { (permissions, response) =>
        runTask {
          response.status shouldEqual StatusCodes.OK
          val body =
            jsonContentOf(
              "/iam/permissions/replace.json",
              permissionDsl.permissionsRepl(
                Permission.minimalPermissions + permission1 + permission2
              )
            )
          deltaClient.put[Json](s"/permissions?rev=${permissions._rev}", body, Identity.ServiceAccount) {
            (_, response) =>
              response.status shouldEqual StatusCodes.OK
          }
        }
      }
    }

    "check replaced permissions" in {
      deltaClient.get[Permissions]("/permissions", Identity.ServiceAccount) { (permissions, response) =>
        response.status shouldEqual StatusCodes.OK
        permissions.permissions shouldEqual Permission.minimalPermissions + permission1 + permission2
      }
    }

    "reject subtracting minimal permission" in {
      deltaClient.get[Permissions]("/permissions", Identity.ServiceAccount) { (permissions, response) =>
        runTask {
          response.status shouldEqual StatusCodes.OK
          val body = jsonContentOf(
            "/iam/permissions/subtract.json",
            permissionDsl.permissionsRepl(
              Permission.minimalPermissions.take(1)
            )
          )
          deltaClient.patch[Json](s"/permissions?rev=${permissions._rev}", body, Identity.ServiceAccount) {
            (_, response) =>
              response.status shouldEqual StatusCodes.BadRequest
          }
        }
      }
    }

    "reject replacing minimal permission" in {
      deltaClient.get[Permissions]("/permissions", Identity.ServiceAccount) { (permissions, response) =>
        runTask {
          response.status shouldEqual StatusCodes.OK
          val body = jsonContentOf(
            "/iam/permissions/replace.json",
            permissionDsl.permissionsRepl(
              Permission.minimalPermissions.subsets(1).next()
            )
          )
          deltaClient.put[Json](s"/permissions?rev=${permissions._rev}", body, Identity.ServiceAccount) {
            (_, response) =>
              response.status shouldEqual StatusCodes.BadRequest
          }
        }
      }
    }
  }
}
