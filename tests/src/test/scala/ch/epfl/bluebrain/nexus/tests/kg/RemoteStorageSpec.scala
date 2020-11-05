package ch.epfl.bluebrain.nexus.tests.kg

import java.io.File
import java.nio.file.{Files, Paths}

import akka.http.scaladsl.model.StatusCodes
import ch.epfl.bluebrain.nexus.tests.HttpClient._
import ch.epfl.bluebrain.nexus.tests.Identity
import ch.epfl.bluebrain.nexus.tests.Optics.{filterKey, filterMetadataKeys}
import ch.epfl.bluebrain.nexus.tests.Tags.StorageTag
import ch.epfl.bluebrain.nexus.tests.iam.types.Permission
import io.circe.Json
import monix.bio.Task
import org.scalatest.Assertion

import scala.reflect.io.Directory

class RemoteStorageSpec extends StorageSpec {

  override def storageType: String = "external"

  override def storageName: String = "myexternalstorage"

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
      "id"       -> storageName
    )

    val payload2 = jsonContentOf(
      "/kg/storages/remote-disk.json",
      "endpoint" -> externalEndpoint,
      "cred"     -> serviceAccountToken,
      "read"     -> s"$storageType/read",
      "write"    -> s"$storageType/write",
      "folder"   -> remoteFolder,
      "id"       -> s"${storageName}2"
    )

    for {
      _ <- deltaClient.post[Json](s"/storages/$fullId", payload, Coyote) { (_, response) =>
             response.status shouldEqual StatusCodes.Created
           }
      _ <- deltaClient.get[Json](s"/storages/$fullId/nxv:$storageName", Coyote) { (json, response) =>
             val expected = jsonContentOf(
               "/kg/storages/remote-disk-response.json",
               replacements(
                 Coyote,
                 "endpoint"    -> externalEndpoint,
                 "folder"      -> remoteFolder,
                 "id"          -> s"nxv:$storageName",
                 "project"     -> fullId,
                 "maxFileSize" -> storageConfig.maxFileSize.toString,
                 "read"        -> "resources/read",
                 "write"       -> "files/write"
               ): _*
             )
             filterMetadataKeys(json) should equalIgnoreArrayOrder(expected)
             response.status shouldEqual StatusCodes.OK
           }
      _ <- deltaClient.get[Json](s"/storages/$fullId/nxv:$storageName/source", Coyote) { (json, response) =>
             response.status shouldEqual StatusCodes.OK
             val expected = jsonContentOf(
               "/kg/storages/storage-source.json",
               "folder"      -> remoteFolder,
               "storageBase" -> externalEndpoint
             )
             json should equalIgnoreArrayOrder(expected)

           }
      _ <- permissionDsl.addPermissions(
             Permission(storageType, "read"),
             Permission(storageType, "write")
           )
      _ <- deltaClient.post[Json](s"/storages/$fullId", payload2, Coyote) { (_, response) =>
             response.status shouldEqual StatusCodes.Created
           }
      _ <- deltaClient.get[Json](s"/storages/$fullId/nxv:${storageName}2", Coyote) { (json, response) =>
             val expected = jsonContentOf(
               "/kg/storages/remote-disk-response.json",
               replacements(
                 Coyote,
                 "endpoint"    -> externalEndpoint,
                 "folder"      -> remoteFolder,
                 "id"          -> s"nxv:${storageName}2",
                 "project"     -> fullId,
                 "maxFileSize" -> storageConfig.maxFileSize.toString,
                 "read"        -> s"$storageType/read",
                 "write"       -> s"$storageType/write"
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
        "id"       -> storageName
      )

      deltaClient.post[Json](s"/storages/$fullId", filterKey("folder")(payload), Coyote) { (_, response) =>
        response.status shouldEqual StatusCodes.BadRequest
      }
    }
  }
}
