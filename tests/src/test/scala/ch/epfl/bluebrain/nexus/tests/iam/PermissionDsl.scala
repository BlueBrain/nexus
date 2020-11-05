package ch.epfl.bluebrain.nexus.tests.iam

import akka.http.scaladsl.model.StatusCodes
import ch.epfl.bluebrain.nexus.testkit.TestHelpers
import ch.epfl.bluebrain.nexus.tests.iam.types.{Permission, Permissions}
import ch.epfl.bluebrain.nexus.tests.{CirceUnmarshalling, HttpClient, Identity}
import io.circe.Json
import monix.bio.Task
import monix.execution.Scheduler.Implicits.global
import org.scalatest.Assertion
import org.scalatest.matchers.should.Matchers

class PermissionDsl(cl: HttpClient) extends TestHelpers with CirceUnmarshalling with Matchers {

  def permissionsRepl(permissions: Iterable[Permission]) =
    "perms" -> permissions.map { _.value }.mkString("\",\"")

  def addPermissions(list: Permission*): Task[Assertion] =
    cl.get[Permissions]("/permissions", Identity.ServiceAccount) { (permissions, response) =>
      response.status shouldEqual StatusCodes.OK
      val body = jsonContentOf(
        "/iam/permissions/append.json",
        permissionsRepl(list)
      )
      if (!list.toSet.subsetOf(permissions.permissions)) {
        cl.patch[Json](s"/permissions?rev=${permissions._rev}", body, Identity.ServiceAccount) { (_, response) =>
          response.status shouldEqual StatusCodes.OK
        }.runSyncUnsafe()
      } else {
        succeed
      }

    }

}
