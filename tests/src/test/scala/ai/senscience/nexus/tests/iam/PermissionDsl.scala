package ai.senscience.nexus.tests.iam

import ai.senscience.nexus.tests.iam.types.{Permission, Permissions}
import ai.senscience.nexus.tests.{HttpClient, Identity}
import akka.http.scaladsl.model.StatusCodes
import cats.effect.IO
import cats.effect.unsafe.implicits.*
import ch.epfl.bluebrain.nexus.akka.marshalling.CirceUnmarshalling
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClasspathResourceLoader
import io.circe.Json
import org.scalatest.Assertion
import org.scalatest.matchers.should.Matchers

class PermissionDsl(cl: HttpClient) extends CirceUnmarshalling with Matchers {

  private val loader = ClasspathResourceLoader()

  def permissionsRepl(permissions: Iterable[Permission]) =
    "perms" -> permissions.map { _.value }.mkString("\",\"")

  def addPermissions(list: Permission*): IO[Assertion] =
    cl.get[Permissions]("/permissions", Identity.ServiceAccount) { (permissions, response) =>
      response.status shouldEqual StatusCodes.OK

      if (!list.toSet.subsetOf(permissions.permissions)) {
        (for {
          body   <- loader.jsonContentOf(
                      "iam/permissions/append.json",
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
