package ch.epfl.bluebrain.nexus.tests.iam

import akka.http.scaladsl.model.StatusCodes
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UrlUtils.encode
import ch.epfl.bluebrain.nexus.tests.BaseSpec
import ch.epfl.bluebrain.nexus.tests.Identity.userPermissions.{UserWithNoPermissions, UserWithPermissions}
import ch.epfl.bluebrain.nexus.tests.iam.types.Permission.Resources

class UserPermissionsSpec extends BaseSpec {

  val org, project = genId()

  private def urlFor(permission: String, project: String) =
    s"/user/permissions/$project?permission=${encode(permission)}"

  "if a user does not have a permission, 403 should be returned" in {
    deltaClient.head(urlFor("resources/read", s"$org/$project"), UserWithNoPermissions) { response =>
      response.status shouldBe StatusCodes.Forbidden
    }
  }

  "if a user has a permission, 204 should be returned" in {
    for {
      _ <- aclDsl.addPermission(s"/$org/$project", UserWithPermissions, Resources.Read)
      _ <- deltaClient.head(urlFor("resources/read", s"$org/$project"), UserWithPermissions) { response =>
             response.status shouldBe StatusCodes.NoContent
           }
    } yield succeed
  }
}
