package ai.senscience.nexus.tests.kg.files

import ai.senscience.nexus.tests.HttpClient
import ai.senscience.nexus.tests.Identity.storages.Coyote
import ai.senscience.nexus.tests.Optics.{filterKey, filterMetadataKeys}
import akka.http.scaladsl.model.*
import cats.effect.IO
import ch.epfl.bluebrain.nexus.akka.marshalling.CirceUnmarshalling
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClasspathResourceLoader
import ch.epfl.bluebrain.nexus.testkit.CirceEq
import io.circe.Json
import org.scalatest.*
import org.scalatest.matchers.should.Matchers

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

  def diskPayloadDefaultPerms(id: String): IO[Json] =
    loader.jsonContentOf("kg/storages/disk.json", "id" -> id)

  def diskPayload(id: String, read: String, write: String): IO[Json] =
    loader.jsonContentOf("kg/storages/disk-perms.json", "id" -> id, "read" -> read, "write" -> write)
}

object StoragesDsl {
  val StorageServiceBaseUrl: String = "http://storage-service:8080/v1"
}
