package ch.epfl.bluebrain.nexus.tests.kg

import akka.http.scaladsl.model.{ContentTypes, HttpCharsets, MediaTypes, StatusCodes}
import akka.util.ByteString
import ch.epfl.bluebrain.nexus.tests.HttpClient._
import ch.epfl.bluebrain.nexus.tests.Identity
import ch.epfl.bluebrain.nexus.tests.Identity.storages.Coyote
import ch.epfl.bluebrain.nexus.tests.Optics.{filterKey, filterMetadataKeys, projections}
import ch.epfl.bluebrain.nexus.tests.iam.types.Permission
import ch.epfl.bluebrain.nexus.tests.iam.types.Permission.Supervision
import io.circe.generic.semiauto.deriveDecoder
import io.circe.{Decoder, Json}
import monix.bio.Task
import org.scalactic.source.Position
import org.scalatest.Assertion

import scala.annotation.nowarn
import scala.sys.process._

class RemoteStorageSpec extends StorageSpec {

  override def storageName: String = "external"

  override def storageType: String = "RemoteDiskStorage"

  override def storageId: String = "myexternalstorage"

  override def locationPrefix: Option[String] = Some(s"file:///tmp/$remoteFolder")

  val externalEndpoint: String = s"http://storage-service:8080/v1"
  private val remoteFolder     = genId()

  override def beforeAll(): Unit = {
    super.beforeAll()
    val createFolder = s"mkdir -p /tmp/$remoteFolder/protected"
    s"docker exec nexus-storage-service bash -c \"$createFolder\"".!
    ()
  }

  override def afterAll(): Unit = {
    super.afterAll()
    val deleteFolder = s"rm -rf /tmp/$remoteFolder"
    s"docker exec nexus-storage-service bash -c \"$deleteFolder\"".!
    ()
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

  def putFile(name: String, content: String, storageId: String)(implicit position: Position) = {
    deltaClient.putAttachment[Json](
      s"/files/$fullId/test-resource:$name?storage=nxv:${storageId}",
      content,
      MediaTypes.`text/plain`.toContentType(HttpCharsets.`UTF-8`),
      name,
      Coyote
    ) { (json, response) =>
      println(json)
      response.status shouldEqual StatusCodes.Created
    }
  }

  def randomString(length: Int) = {
    val r = new scala.util.Random
    val sb = new StringBuilder
    for (_ <- 1 to length) {
      sb.append(r.nextPrintableChar())
    }
    sb.toString
  }

  "succeed many large files are in the archive, going over the time limit" in {
    val content = randomString(130000000)
    val payload = jsonContentOf("/kg/archives/archive-many-large-files.json")
    var before = 0L
    for {
      _ <- putFile("largefile1.txt", content, s"${storageId}2")
      _ <- putFile("largefile2.txt", content, s"${storageId}2")
      _ <- putFile("largefile3.txt", content, s"${storageId}2")
      _ <- putFile("largefile4.txt", content, s"${storageId}2")
      _ <- putFile("largefile5.txt", content, s"${storageId}2")
      _ <- putFile("largefile6.txt", content, s"${storageId}2")
      _ <- putFile("largefile7.txt", content, s"${storageId}2")
      _ <- putFile("largefile8.txt", content, s"${storageId}2")
      _ <- putFile("largefile9.txt", content, s"${storageId}2")
      _ <- putFile("largefile10.txt", content, s"${storageId}2")
      _ <- putFile("largefile11.txt", content, s"${storageId}2")
      _ <- putFile("largefile12.txt", content, s"${storageId}2")
      _ <- putFile("largefile13.txt", content, s"${storageId}2")
      _ <- putFile("largefile14.txt", content, s"${storageId}2")
      _ <- putFile("largefile15.txt", content, s"${storageId}2")
      _ <- deltaClient.put[ByteString](s"/archives/$fullId/nxv:very-large-archive", payload, Coyote) {
        (_, response) =>
          before = System.currentTimeMillis()
          response.status shouldEqual StatusCodes.Created
      }
      _ <- deltaClient.get[ByteString](s"/archives/$fullId/nxv:very-large-archive", Coyote, acceptAll) { (_, response) =>
        println(s"time taken to download archive: ${System.currentTimeMillis() - before}ms")
        response.status shouldEqual StatusCodes.OK
        contentType(response) shouldEqual MediaTypes.`application/x-tar`.toContentType
      }
    } yield {
      succeed
    }
  }

  "creating a remote storage" should {
    "fail creating a RemoteDiskStorage without folder" in {
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

  s"Linking in Remote storage" should {
    "link an existing file" in {
      val createFile = s"echo 'file content' > /tmp/$remoteFolder/file.txt"
      s"docker exec nexus-storage-service bash -c \"$createFile\"".!

      val payload = Json.obj(
        "filename"  -> Json.fromString("file.txt"),
        "path"      -> Json.fromString(s"file.txt"),
        "mediaType" -> Json.fromString("text/plain")
      )

      val expected = jsonContentOf(
        "/kg/files/remote-linked.json",
        replacements(
          Coyote,
          "id"          -> s"${config.deltaUri}/resources/$fullId/_/file.txt",
          "filename"    -> "file.txt",
          "storageId"   -> s"${storageId}2",
          "storageType" -> storageType,
          "projId"      -> s"$fullId",
          "project"     -> s"${config.deltaUri}/projects/$fullId"
        ): _*
      )

      deltaClient.put[Json](s"/files/$fullId/file.txt?storage=nxv:${storageId}2", payload, Coyote) { (json, response) =>
        filterMetadataKeys.andThen(filterKey("_location"))(json) shouldEqual expected
        response.status shouldEqual StatusCodes.Created
      }
    }

    "fetch eventually a linked file with updated attributes" in eventually {
      val expected = jsonContentOf(
        "/kg/files/remote-updated-linked.json",
        replacements(
          Coyote,
          "id"          -> s"${config.deltaUri}/resources/$fullId/_/file.txt",
          "filename"    -> "file.txt",
          "storageId"   -> s"${storageId}2",
          "storageType" -> storageType,
          "projId"      -> s"$fullId",
          "project"     -> s"${config.deltaUri}/projects/$fullId"
        ): _*
      )

      deltaClient.get[Json](s"/files/$fullId/file.txt", Coyote) { (json, response) =>
        response.status shouldEqual StatusCodes.OK
        filterMetadataKeys.andThen(filterKey("_location"))(json) shouldEqual expected
      }
    }

    "fail to link a nonexistent file" in {
      val payload = Json.obj(
        "filename"  -> Json.fromString("logo.png"),
        "path"      -> Json.fromString("non/existent.png"),
        "mediaType" -> Json.fromString("image/png")
      )

      deltaClient.put[Json](s"/files/$fullId/nonexistent.png?storage=nxv:${storageId}2", payload, Coyote) {
        (_, response) =>
          response.status shouldEqual StatusCodes.BadRequest
      }
    }
  }

  "The file-attributes-updated projection description" should {
    "exist" in {
      aclDsl.addPermission("/", Coyote, Supervision.Read).accepted
      deltaClient.get[Json]("/supervision/projections", Coyote) { (json, _) =>
        val expected = json"""{ "module": "system", "name": "file-attributes-update" }"""
        assert(projections.metadata.json.exist(_ == expected)(json))
      }
    }

    "have updated progress when a file is updated" in {
      case class SupervisedDescription(metadata: Metadata, progress: ProjectionProgress)
      case class Metadata(module: String, name: String)
      case class ProjectionProgress(processed: Int)
      @nowarn("cat=unused")
      implicit val metadataDecoder: Decoder[Metadata]                 = deriveDecoder
      @nowarn("cat=unused")
      implicit val progressDecoder: Decoder[ProjectionProgress]       = deriveDecoder
      implicit val descriptionDecoder: Decoder[SupervisedDescription] = deriveDecoder

      /**
        * Given a list of supervised descriptions (json), get the number of processed elements for the
        * `file-attribute-update` projection
        */
      def getProcessed(json: Json): Option[Int] = {
        val Right(projections)      = json.hcursor.downField("projections").as[List[SupervisedDescription]]
        val fileAttributeProjection =
          projections.find(p => p.metadata.name == "file-attribute-update" && p.metadata.module == "system")
        fileAttributeProjection.map(_.progress.processed)
      }

      // get progress prior to updating the file
      deltaClient.get[Json]("/supervision/projections", Coyote) { (json1, _) =>
        eventually {
          // update the file
          deltaClient.putAttachment[Json](
            s"/files/$fullId/file.txt?storage=nxv:${storageId}2&rev=2",
            contentOf("/kg/files/attachment.json"),
            ContentTypes.`application/json`,
            "file.txt",
            Coyote
          ) { (_, _) =>
            eventually {
              // get progress after the file update and compare
              deltaClient.get[Json]("/supervision/projections", Coyote) { (json2, _) =>
                assert(getProcessed(json2) == getProcessed(json1).map(_ + 1))
              }
            }
          }
        }
      }
    }

  }
}
