package ch.epfl.bluebrain.nexus.tests.iam

import akka.http.scaladsl.model.StatusCodes
import cats.effect.IO
import cats.effect.unsafe.IORuntime
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClasspathResourceUtils.ioJsonContentOf
import ch.epfl.bluebrain.nexus.tests.iam.types.{Permission, Permissions}
import ch.epfl.bluebrain.nexus.tests.{CirceUnmarshalling, HttpClient, Identity}
import io.circe.Json
import org.scalatest.Assertion
import org.scalatest.matchers.should.Matchers

class PermissionDsl(cl: HttpClient)(implicit runtime: IORuntime) extends CirceUnmarshalling with Matchers {

  def permissionsRepl(permissions: Iterable[Permission]) =
    "perms" -> permissions.map { _.value }.mkString("\",\"")

  def addPermissions(list: Permission*): IO[Assertion] =
    cl.get[Permissions]("/permissions", Identity.ServiceAccount) { (permissions, response) =>
      response.status shouldEqual StatusCodes.OK

      if (!list.toSet.subsetOf(permissions.permissions)) {
        (for {
          body   <- ioJsonContentOf(
                      "/iam/permissions/append.json",
                      permissionsRepl(list)
                    )
          result <-
            cl.patch[Json](s"/permissions?rev=${permissions._rev}", body, Identity.ServiceAccount) { (_, response) =>
              response.status shouldEqual StatusCodes.OK
            }
        } yield result).unsafeRunSync()
      } else {
        succeed
      }

    }

}
