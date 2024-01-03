package ch.epfl.bluebrain.nexus.tests.kg.files

import akka.http.scaladsl.model._
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClasspathResourceLoader
import ch.epfl.bluebrain.nexus.testkit.CirceEq
import ch.epfl.bluebrain.nexus.tests.Identity.storages.Coyote
import ch.epfl.bluebrain.nexus.tests.Optics.{filterKey, filterMetadataKeys}
import ch.epfl.bluebrain.nexus.tests.{CirceUnmarshalling, HttpClient}
import io.circe.Json
import org.scalatest._
import org.scalatest.matchers.should.Matchers

import scala.sys.process._

class StoragesDsl(deltaClient: HttpClient) extends CirceUnmarshalling with Matchers with CirceEq {

  private val loader = ClasspathResourceLoader()

  def createStorage(payload: Json, projectRef: String): IO[Assertion] =
    deltaClient.post[Json](s"/storages/$projectRef", payload, Coyote) { (_, response) =>
      response.status shouldEqual StatusCodes.Created
    }

  def createDiskStorageWithDefaultPerms(id: String, projectRef: String): IO[Assertion] =
    diskPayloadDefaultPerms(id).flatMap(createStorage(_, projectRef))

  def createDiskStorageWithCustomPerms(id: String, projectRef: String, read: String, write: String): IO[Assertion] =
    diskPayload(id, read, write).flatMap(createStorage(_, projectRef))

  def createRemoteStorageWithDefaultPerms(id: String, projectRef: String, folder: String): IO[Assertion] =
    remoteDiskPayloadDefaultPerms(id, folder).flatMap(createStorage(_, projectRef))

  def createRemoteStorageWithCustomPerms(
      id: String,
      projectRef: String,
      folder: String,
      read: String,
      write: String
  ): IO[Assertion] =
    remoteDiskPayload(id, folder, read, write).flatMap(createStorage(_, projectRef))

  def checkStorageMetadata(projectRef: String, storageId: String, expected: Json): IO[Assertion] =
    deltaClient.get[Json](s"/storages/$projectRef/nxv:$storageId", Coyote) { (json, response) =>
      response.status shouldEqual StatusCodes.OK
      filterMetadataKeys(json) should equalIgnoreArrayOrder(expected)
    }

  def checkStorageSource(projectRef: String, storageId: String, expected: Json): IO[Assertion] =
    deltaClient.get[Json](s"/storages/$projectRef/nxv:$storageId/source", Coyote) { (json, response) =>
      response.status shouldEqual StatusCodes.OK
      filterKey("credentials")(json) should equalIgnoreArrayOrder(expected)
    }

  def remoteDiskPayloadDefaultPerms(id: String, folder: String): IO[Json] =
    remoteDiskPayload(id, folder, "resources/read", "files/write")

  def remoteDiskPayload(id: String, folder: String, readPerm: String, writePerm: String): IO[Json] =
    loader.jsonContentOf(
      "kg/storages/remote-disk.json",
      "endpoint" -> StoragesDsl.StorageServiceBaseUrl,
      "read"     -> readPerm,
      "write"    -> writePerm,
      "folder"   -> folder,
      "id"       -> id
    )

  def diskPayloadDefaultPerms(id: String): IO[Json]                  =
    loader.jsonContentOf("kg/storages/disk.json", "id" -> id)

  def diskPayload(id: String, read: String, write: String): IO[Json] =
    loader.jsonContentOf("kg/storages/disk-perms.json", "id" -> id, "read" -> read, "write" -> write)

  def mkProtectedFolderInStorageService(folder: String): IO[Unit]    =
    runCommandInStorageService(s"mkdir -p /tmp/$folder/protected")

  def deleteFolderInStorageService(folder: String): IO[Unit] =
    runCommandInStorageService(s"rm -rf /tmp/$folder")

  def runCommandInStorageService(cmd: String): IO[Unit] =
    runBlockingProcess(s"docker exec nexus-storage-service bash -c \"$cmd\"")

  private def runBlockingProcess(cmd: String): IO[Unit] = IO.blocking(cmd.!).flatMap {
    case 0     => IO.unit
    case other => IO.raiseError(new Exception(s"Command $cmd failed with code $other"))
  }
}

object StoragesDsl {
  val StorageServiceBaseUrl: String = "http://storage-service:8080/v1"
}
