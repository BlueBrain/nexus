package ch.epfl.bluebrain.nexus.tests.kg

import akka.http.scaladsl.model.StatusCodes
import ch.epfl.bluebrain.nexus.tests.HttpClient._
import ch.epfl.bluebrain.nexus.tests.Identity
import ch.epfl.bluebrain.nexus.tests.Identity.storages.Coyote
import ch.epfl.bluebrain.nexus.tests.Optics.{filterKey, filterMetadataKeys}
import ch.epfl.bluebrain.nexus.tests.Tags.StorageTag
import ch.epfl.bluebrain.nexus.tests.iam.types.Permission
import io.circe.Json
import monix.bio.Task
import org.scalatest.Assertion

import java.io.File
import java.nio.file.{Files, Paths}
import scala.reflect.io.Directory

class RemoteStorageSpec extends StorageSpec {

  override def storageName: String = "external"

  override def storageType: String = "RemoteDiskStorage"

  override def storageId: String = "myexternalstorage"

  override def locationPrefix: Option[String] = Some(s"file:///data/$remoteFolder")

  val externalEndpoint: String = s"http://storage-service:8080/v1"
  private val remoteFolder     = genId()

  override def beforeAll(): Unit = {
    super.beforeAll()
    // Create folder for remote storage
    Files.createDirectories(Paths.get(s"/tmp/storage/$remoteFolder/protected"))
    ()
  }

  override def afterAll(): Unit = {
    new Directory(new File(s"/tmp/storage/$remoteFolder")).deleteRecursively()
    super.afterAll()
  }

  private def serviceAccountToken = tokensMap.get(Identity.ServiceAccount).credentials.token()

  override def createStorages: Task[Assertion] = {
    val payload = jsonContentOf(
      "/kg/storages/remote-disk.json",
      "endpoint" -> externalEndpoint,
      "cred"     -> serviceAccountToken,
      "read"     -> "resources/read",
      "write"    -> "files/write",
      "folder"   -> remoteFolder,
      "id"       -> storageId
    )

    val payload2 = jsonContentOf(
      "/kg/storages/remote-disk.json",
      "endpoint" -> externalEndpoint,
      "cred"     -> serviceAccountToken,
      "read"     -> s"$storageName/read",
      "write"    -> s"$storageName/write",
      "folder"   -> remoteFolder,
      "id"       -> s"${storageId}2"
    )

    for {
      _ <- deltaClient.post[Json](s"/storages/$fullId", payload, Coyote) { (json, response) =>
             if (response.status != StatusCodes.Created) {
               fail(s"Unexpected status '${response.status}', response:\n${json.spaces2}")
             } else succeed
           }
      _ <- deltaClient.get[Json](s"/storages/$fullId/nxv:$storageId", Coyote) { (json, response) =>
             val expected = jsonContentOf(
               "/kg/storages/remote-disk-response.json",
               replacements(
                 Coyote,
                 "endpoint"    -> externalEndpoint,
                 "folder"      -> remoteFolder,
                 "id"          -> storageId,
                 "project"     -> fullId,
                 "maxFileSize" -> storageConfig.maxFileSize.toString,
                 "read"        -> "resources/read",
                 "write"       -> "files/write"
               ): _*
             )
             filterMetadataKeys(json) should equalIgnoreArrayOrder(expected)
             response.status shouldEqual StatusCodes.OK
           }
      _ <- deltaClient.get[Json](s"/storages/$fullId/nxv:$storageId/source", Coyote) { (json, response) =>
             response.status shouldEqual StatusCodes.OK
             val expected = jsonContentOf(
               "/kg/storages/storage-source.json",
               "folder"      -> remoteFolder,
               "storageBase" -> externalEndpoint
             )
             filterKey("credentials")(json) should equalIgnoreArrayOrder(expected)

           }
      _ <- permissionDsl.addPermissions(
             Permission(storageName, "read"),
             Permission(storageName, "write")
           )
      _ <- deltaClient.post[Json](s"/storages/$fullId", payload2, Coyote) { (_, response) =>
             response.status shouldEqual StatusCodes.Created
           }
      _ <- deltaClient.get[Json](s"/storages/$fullId/nxv:${storageId}2", Coyote) { (json, response) =>
             val expected = jsonContentOf(
               "/kg/storages/remote-disk-response.json",
               replacements(
                 Coyote,
                 "endpoint"    -> externalEndpoint,
                 "folder"      -> remoteFolder,
                 "id"          -> s"${storageId}2",
                 "project"     -> fullId,
                 "maxFileSize" -> storageConfig.maxFileSize.toString,
                 "read"        -> s"$storageName/read",
                 "write"       -> s"$storageName/write"
               ): _*
             )
             filterMetadataKeys(json) should equalIgnoreArrayOrder(expected)
             response.status shouldEqual StatusCodes.OK
           }
    } yield succeed
  }

  "creating a remote storage" should {
    "fail creating a RemoteDiskStorage without folder" taggedAs StorageTag in {
      val payload = jsonContentOf(
        "/kg/storages/remote-disk.json",
        "endpoint" -> externalEndpoint,
        "cred"     -> serviceAccountToken,
        "read"     -> "resources/read",
        "write"    -> "files/write",
        "folder"   -> "nexustest",
        "id"       -> storageId
      )

      deltaClient.post[Json](s"/storages/$fullId", filterKey("folder")(payload), Coyote) { (_, response) =>
        response.status shouldEqual StatusCodes.BadRequest
      }
    }
  }
}
