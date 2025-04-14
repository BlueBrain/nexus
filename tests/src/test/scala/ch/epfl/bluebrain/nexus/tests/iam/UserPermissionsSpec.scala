package ch.epfl.bluebrain.nexus.tests.iam

import akka.http.scaladsl.model.StatusCodes
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UrlUtils.encode
import ch.epfl.bluebrain.nexus.tests.Identity.userPermissions.{UserWithNoPermissions, UserWithPermissions}
import ch.epfl.bluebrain.nexus.tests.admin.ProjectPayload
import ch.epfl.bluebrain.nexus.tests.iam.types.Permission
import ch.epfl.bluebrain.nexus.tests.iam.types.Permission.Resources
import ch.epfl.bluebrain.nexus.tests.{BaseIntegrationSpec, Identity}
import io.circe.Json
import org.scalactic.source.Position

class UserPermissionsSpec extends BaseIntegrationSpec {

  val org, project           = genId()
  val StorageId              = "https://bluebrain.github.io/nexus/vocabulary/storage1"
  val StorageReadPermission  = Permission("s3-storage", "read")
  val StorageWritePermission = Permission("s3-storage", "write")

  override def beforeAll(): Unit                          = {
    super.beforeAll()
    val result = for {
      _ <- permissionDsl.addPermissions(StorageReadPermission, StorageWritePermission)
      _ <- adminDsl.createOrganization(org, "UserPermissionsSpec organisation", Identity.ServiceAccount)
      _ <- adminDsl.createProject(org, project, ProjectPayload.generate(project), Identity.ServiceAccount)
      _ <- createStorage(StorageId, StorageReadPermission, StorageWritePermission)
    } yield succeed

    result.accepted
    ()
  }
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

  private def storageUrlFor(project: String, storageId: String, typ: String): String = {
    s"/user/permissions/$project?storage=${encode(storageId)}&type=$typ"
  }

  "if a user does not have read permission for a storage, 403 should be returned" in {
    deltaClient.head(storageUrlFor(s"$org/$project", StorageId, "read"), UserWithNoPermissions) { response =>
      response.status shouldBe StatusCodes.Forbidden
    }
  }

  "if a user has read permission for a storage, 204 should be returned" in {
    for {
      _ <- aclDsl.addPermission(s"/$org/$project", UserWithPermissions, StorageReadPermission)
      _ <- deltaClient.head(storageUrlFor(s"$org/$project", StorageId, "read"), UserWithPermissions) { response =>
             response.status shouldBe StatusCodes.NoContent
           }
    } yield succeed
  }

  "if a user does not have write permission for a storage, 403 should be returned" in {
    deltaClient.head(storageUrlFor(s"$org/$project", StorageId, "write"), UserWithNoPermissions) { response =>
      response.status shouldBe StatusCodes.Forbidden
    }
  }

  "if a user has write permission for a storage, 204 should be returned" in {
    for {
      _ <- aclDsl.addPermission(s"/$org/$project", UserWithPermissions, StorageWritePermission)
      _ <- deltaClient.head(storageUrlFor(s"$org/$project", StorageId, "write"), UserWithPermissions) { response =>
             response.status shouldBe StatusCodes.NoContent
           }
    } yield succeed
  }

  private def createStorage(id: String, readPermission: Permission, writePermission: Permission)(implicit
      pos: Position
  ) = {
    val payload = jsonContentOf(
      "kg/storages/disk-perms-parameterised.json",
      "id"               -> id,
      "read-permission"  -> readPermission.value,
      "write-permission" -> writePermission.value
    )
    deltaClient.post[Json](s"/storages/$org/$project", payload, Identity.ServiceAccount) { (_, response) =>
      withClue("creation of storage failed: ") {
        response.status shouldEqual StatusCodes.Created
      }
    }
  }
}
