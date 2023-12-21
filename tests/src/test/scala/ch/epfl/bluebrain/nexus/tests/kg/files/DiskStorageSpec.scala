package ch.epfl.bluebrain.nexus.tests.kg.files

import akka.http.scaladsl.model.StatusCodes
import cats.effect.IO
import ch.epfl.bluebrain.nexus.tests.Identity.storages.Coyote
import ch.epfl.bluebrain.nexus.tests.iam.types.Permission
import io.circe.Json
import org.scalatest.Assertion

class DiskStorageSpec extends StorageSpec {

  override def storageName: String = "disk"

  override def storageType: String = "DiskStorage"

  override def storageId: String = "mystorage"

  override def locationPrefix: Option[String] = None

  private def storageResponse(project: String, id: String, readPermission: String, writePermission: String) =
    jsonContentOf(
      "kg/storages/disk-response.json",
      replacements(
        Coyote,
        "id"          -> id,
        "project"     -> project,
        "self"        -> storageSelf(project, s"https://bluebrain.github.io/nexus/vocabulary/$id"),
        "read"        -> readPermission,
        "maxFileSize" -> storageConfig.maxFileSize.toString,
        "write"       -> writePermission
      ): _*
    )

  override def createStorages(projectRef: String, storId: String, storName: String): IO[Assertion] = {
    val storageId2    = s"${storId}2"
    val storage2Read  = s"$storName/read"
    val storage2Write = s"$storName/write"

    val expectedStorage = storageResponse(projectRef, storId, "resources/read", "files/write")
    val expectedStorageWithPerms = storageResponse(projectRef, storageId2, storage2Read, storage2Write)

    for {
      _ <- storagesDsl.createDiskStorageDefaultPerms(storId, projectRef)
      _ <- storagesDsl.checkStorageMetadata(projectRef, storId, expectedStorage)
      _ <- permissionDsl.addPermissions(Permission(storName, "read"), Permission(storName, "write"))
      _ <- storagesDsl.createDiskStorageCustomPerms(storageId2, projectRef, storage2Read, storage2Write)
      _ <- storagesDsl.checkStorageMetadata(projectRef, storageId2, expectedStorageWithPerms)
    } yield succeed
  }

  "creating a disk storage" should {
    "fail creating a DiskStorage on a wrong volume" in {
      val volume  = "/" + genString()
      val payload = jsonContentOf("kg/storages/disk.json") deepMerge
        Json.obj(
          "@id"    -> Json.fromString("https://bluebrain.github.io/nexus/vocabulary/invalid-volume"),
          "volume" -> Json.fromString(volume)
        )

      deltaClient.post[Json](s"/storages/$projectRef", payload, Coyote) { (json, response) =>
        json shouldEqual jsonContentOf("kg/storages/error.json", "volume" -> volume)
        response.status shouldEqual StatusCodes.BadRequest
      }
    }
  }

  s"Linking against the default storage" should {
    "reject linking operations" in {
      val payload = Json.obj(
        "filename"  -> Json.fromString("logo.png"),
        "path"      -> Json.fromString("does/not/matter"),
        "mediaType" -> Json.fromString("image/png")
      )

      deltaClient.put[Json](s"/files/$projectRef/linking.png", payload, Coyote) { (json, response) =>
        response.status shouldEqual StatusCodes.BadRequest
        json shouldEqual jsonContentOf("kg/files/linking-notsupported.json", "org" -> orgId, "proj" -> projId)
      }
    }
  }
}
