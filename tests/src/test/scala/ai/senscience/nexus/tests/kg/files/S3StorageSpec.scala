package ai.senscience.nexus.tests.kg.files

import ai.senscience.nexus.tests.HttpClient.acceptAll
import ai.senscience.nexus.tests.Identity.storages.Coyote
import ai.senscience.nexus.tests.Optics
import ai.senscience.nexus.tests.Optics.{error, filterMetadataKeys, location}
import ai.senscience.nexus.tests.iam.types.Permission
import ai.senscience.nexus.tests.kg.files.FilesAssertions.expectFileContent
import ai.senscience.nexus.tests.kg.files.model.FileInput
import akka.http.scaladsl.model.{ContentTypes, MediaTypes, StatusCodes}
import akka.util.ByteString
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UrlUtils.{decodeUri, encodeUriPath}
import ch.epfl.bluebrain.nexus.testkit.scalatest.FileMatchers.{digest as digestField, filename as filenameField, mediaType as mediaTypeField}
import io.circe.Json
import io.circe.syntax.{EncoderOps, KeyOps}
import io.laserdisc.pure.s3.tagless.S3AsyncClientOp
import org.apache.commons.codec.binary.Hex
import org.scalatest.Assertion
import software.amazon.awssdk.services.s3.model.*

import java.util.Base64
import scala.jdk.CollectionConverters.*

class S3StorageSpec extends StorageSpec with S3ClientFixtures {

  override def storageName: String = "s3"

  override def storageType: String = "S3Storage"

  override def storageId: String = "mys3storage"

  override def locationPrefix: Option[String] = Some(storageConfig.s3.prefix)

  private val bucket = genId()

  implicit private val s3Client: S3AsyncClientOp[IO] = createS3Client.accepted

  override def beforeAll(): Unit = {
    super.beforeAll()
    (createBucket(bucket) >> uploadLogoFileToS3(bucket, logoKey)).accepted
    ()
  }

  override def afterAll(): Unit = {
    cleanupBucket(bucket).accepted
    super.afterAll()
  }

  private def storageResponse(
      project: String,
      id: String,
      expectedBucket: String,
      readPermission: String,
      writePermission: String
  ) =
    jsonContentOf(
      "kg/storages/s3-response.json",
      replacements(
        Coyote,
        "id"          -> id,
        "project"     -> project,
        "self"        -> storageSelf(project, s"https://bluebrain.github.io/nexus/vocabulary/$id"),
        "bucket"      -> expectedBucket,
        "maxFileSize" -> storageConfig.maxFileSize.toString,
        "read"        -> readPermission,
        "write"       -> writePermission
      )*
    )

  override def createStorages(projectRef: String, storId: String, storName: String): IO[Assertion] = {
    val payload = jsonContentOf(
      "kg/storages/s3.json",
      "storageId" -> s"https://bluebrain.github.io/nexus/vocabulary/$storId",
      "bucket"    -> bucket
    )

    val payload2 = jsonContentOf(
      "kg/storages/s3.json",
      "storageId" -> s"https://bluebrain.github.io/nexus/vocabulary/${storId}2",
      "bucket"    -> bucket
    ) deepMerge Json.obj(
      "readPermission"  -> Json.fromString(s"$storName/read"),
      "writePermission" -> Json.fromString(s"$storName/write")
    )

    val expectedStorage          = storageResponse(projectRef, storId, bucket, "resources/read", "files/write")
    val storageId2               = s"${storId}2"
    val expectedStorageWithPerms =
      storageResponse(projectRef, storageId2, bucket, "s3/read", "s3/write")

    for {
      _ <- storagesDsl.createStorage(payload, projectRef)
      _ <- storagesDsl.checkStorageMetadata(projectRef, storId, expectedStorage)
      _ <- permissionDsl.addPermissions(Permission(storName, "read"), Permission(storName, "write"))
      _ <- storagesDsl.createStorage(payload2, projectRef)
      _ <- storagesDsl.checkStorageMetadata(projectRef, storageId2, expectedStorageWithPerms)
    } yield succeed
  }

  "creating a s3 storage" should {
    "fail creating an S3Storage with an invalid bucket" in {
      val payload = jsonContentOf(
        "kg/storages/s3.json",
        "storageId" -> s"https://bluebrain.github.io/nexus/vocabulary/missing",
        "bucket"    -> "foobar"
      )

      deltaClient.post[Json](s"/storages/$projectRef", payload, Coyote) { (json, response) =>
        val stripErrors = error.deleteErrorMessages
        val actual      = stripErrors(json)
        actual shouldBe Json.obj(
          "@context" -> "https://bluebrain.github.io/nexus/contexts/error.json".asJson,
          "@type"    -> "StorageNotAccessible".asJson
        )
        response.status shouldEqual StatusCodes.BadRequest
      }
    }

    "succeed using the configured default bucket when a bucket is not provided" in {
      val defaultBucket = "mydefaultbucket"
      val id            = genId()
      val payload       = jsonContentOf(
        "kg/storages/s3.json",
        "storageId" -> s"https://bluebrain.github.io/nexus/vocabulary/$id"
      ).mapObject(_.remove("bucket"))

      val expectedStorage = storageResponse(projectRef, id, defaultBucket, "resources/read", "files/write")

      for {
        _ <- createBucket(defaultBucket)
        _ <- storagesDsl.createStorage(payload, projectRef)
        _ <- storagesDsl.checkStorageMetadata(projectRef, id, expectedStorage)
      } yield succeed
    }
  }

  "Filenames with url-encodable characters" should {
    "have an appropriate filename in S3" in {
      val id   = genId()
      val name = "name with spaces.txt"
      val file = FileInput(id, name, ContentTypes.`text/plain(UTF-8)`, "file contents")
      deltaClient.uploadFile(projectRef, storageId, file, None) { case (json, response) =>
        response.status shouldEqual StatusCodes.Created
        json should have(filenameField(name))
        val locationValue  = location.getOption(json).value
        locationValue should endWith(encodeUriPath(name))
        val decodeLocation = decodeUri(locationValue)
        assertThereIsAFileInS3WithAtLocation(decodeLocation)
      }
    }
  }

  private def assertThereIsAFileInS3WithAtLocation(location: String): Assertion = {
    s3Client
      .listObjectsV2(ListObjectsV2Request.builder.bucket(bucket).prefix(s"/myprefix/$projectRef/files").build)
      .map(_.contents.asScala.map(_.key()))
      .map(keys => keys should contain(location))
      .accepted
  }

  s"Linking an S3 file" should {

    def createFileLinkNoId(storageId: String, payload: Json) =
      deltaClient.postAndReturn[Json](s"/link/files/$projectRef?storage=nxv:$storageId", payload, Coyote) {
        expectCreated
      }

    def createFileLink(id: String, storageId: String, payload: Json) =
      deltaClient.put[Json](s"/link/files/$projectRef/$id?storage=nxv:$storageId", payload, Coyote) {
        expectCreated
      }

    def updateFileLink(id: String, storageId: String, rev: Int, payload: Json) =
      deltaClient.put[Json](s"/link/files/$projectRef/$id?rev=$rev&storage=nxv:$storageId", payload, Coyote) {
        expectOk
      }

    "succeed without providing an id" in {
      val path    = s"${genId()}/nexus-logo.png"
      val payload = Json.obj("path" := path)

      for {
        _         <- uploadLogoFileToS3(bucket, path)
        json      <- createFileLinkNoId(storageId, payload)
        id         = Optics.`@id`.getOption(json).value
        encodedId  = encodeUriPath(id)
        assertion <- deltaClient.get[Json](s"/files/$projectRef/$encodedId", Coyote) { (json, response) =>
                       response.status shouldEqual StatusCodes.OK
                       filterMetadataKeys(json) shouldEqual linkedFileResponse(
                         projectRef,
                         id,
                         storageId,
                         logoSha256HexDigest,
                         location = path,
                         filename = logoFilename,
                         mediaType = "image/png"
                       )
                     }
      } yield assertion
    }

    "succeed providing an id" in {
      val id      = genId()
      val path    = s"$id/nexus-logo.png"
      val payload = Json.obj("path" := path)

      for {
        _         <- uploadLogoFileToS3(bucket, path)
        _         <- createFileLink(id, storageId, payload)
        fullId     = s"$attachmentPrefix$id"
        assertion <- deltaClient.get[Json](s"/files/$projectRef/$id", Coyote) { (json, response) =>
                       response.status shouldEqual StatusCodes.OK
                       filterMetadataKeys(json) shouldEqual linkedFileResponse(
                         projectRef,
                         fullId,
                         storageId,
                         logoSha256HexDigest,
                         location = path,
                         filename = logoFilename,
                         mediaType = "image/png"
                       )
                     }
      } yield assertion
    }

    "succeed when a content type is supplied" in {
      val id      = genId()
      val path    = s"$id/nexus-logo.png"
      val payload = Json.obj("path" := path, "mediaType" := "image/dan")

      for {
        _         <- uploadLogoFileToS3(bucket, path)
        _         <- createFileLink(id, storageId, payload)
        assertion <- deltaClient.get[Json](s"/files/$projectRef/$id", Coyote) { (json, response) =>
                       response.status shouldEqual StatusCodes.OK
                       json should have(mediaTypeField("image/dan"))
                     }
      } yield assertion
    }

    "succeed with media-type detection" in {
      val id      = genId()
      val path    = s"$id/nexus-logo.custom"
      val payload = Json.obj("path" := path)

      for {
        _         <- uploadLogoFileToS3(bucket, path)
        _         <- createFileLink(id, storageId, payload)
        assertion <- deltaClient.get[Json](s"/files/$projectRef/$id", Coyote) { (json, response) =>
                       response.status shouldEqual StatusCodes.OK
                       json should have(mediaTypeField("application/custom"))
                     }
      } yield assertion
    }

    "be updated" in {
      val id              = genId()
      val fileContent     = genString()
      val filename        = s"${genString()}.txt"
      val originalPath    = s"$id/nexus-logo.png"
      val updatedPath     = s"$id/some/path/$filename"
      val originalPayload = Json.obj("path" := originalPath)
      val updatedPayload  = Json.obj("path" := updatedPath, "mediaType" := "text/plain; charset=UTF-8")

      for {
        _             <- uploadLogoFileToS3(bucket, originalPath)
        _             <- createFileLink(id, storageId, originalPayload)
        s3Digest      <- putFile(bucket, updatedPath, fileContent)
        _             <- updateFileLink(id, storageId, 1, updatedPayload)
        _             <- deltaClient.get[ByteString](s"/files/$projectRef/$id", Coyote, acceptAll) {
                           expectFileContent(
                             filename,
                             MediaTypes.`text/plain`.withMissingCharset,
                             fileContent,
                             cacheable = true
                           )
                         }
        expectedDigest = Hex.encodeHexString(Base64.getDecoder.decode(s3Digest))
        assertion     <- deltaClient.get[Json](s"/files/$projectRef/$id", Coyote) { (json, response) =>
                           response.status shouldEqual StatusCodes.OK
                           json should have(filenameField(filename))
                           json should have(digestField("SHA-256", expectedDigest))
                         }
      } yield assertion
    }
  }

  "Uploading and downloading a large file" should {
    "succeed" in {
      val content   = {
        val sb = new StringBuilder
        (1 to 100_000_000).foreach(_ => sb.append('1'))
        sb.toString()
      }
      val fileInput = FileInput(
        "large-text-file",
        "large-text-file",
        ContentTypes.`text/plain(UTF-8)`,
        content
      )
      for {
        _           <- IO.println("Starting the upload")
        startUpload <- IO.delay(System.currentTimeMillis())
        _           <- deltaClient.uploadFile(projectRef, storageId, fileInput, None) { expectCreated }.timed
        endUpload   <- IO.delay(System.currentTimeMillis())
        _           <- IO.println(s"End of upload after ${endUpload - startUpload}")
        _           <- IO.println("Starting the download")
        _           <- deltaClient.get[ByteString](s"/files/$projectRef/${fileInput.fileId}", Coyote, acceptAll) { expectOk }
        endDownload <- IO.delay(System.currentTimeMillis())
        _           <- IO.println(s"End of download after ${endDownload - endUpload}")
      } yield succeed
    }
  }

  private def linkedFileResponse(
      projectRef: String,
      id: String,
      storageId: String,
      digestValue: String,
      location: String,
      filename: String,
      mediaType: String
  ): Json =
    jsonContentOf(
      "kg/files/linked-metadata.json",
      replacements(
        Coyote,
        "id"          -> id,
        "storageId"   -> storageId,
        "self"        -> fileSelf(projectRef, id),
        "projId"      -> s"$projectRef",
        "digestValue" -> digestValue,
        "location"    -> location,
        "filename"    -> filename,
        "mediaType"   -> mediaType
      )*
    )
}
