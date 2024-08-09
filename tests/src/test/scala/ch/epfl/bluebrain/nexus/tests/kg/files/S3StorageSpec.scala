package ch.epfl.bluebrain.nexus.tests.kg.files

import akka.http.scaladsl.model.{ContentTypes, StatusCodes}
import akka.util.ByteString
import cats.effect.IO
import cats.implicits.toTraverseOps
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UrlUtils
import ch.epfl.bluebrain.nexus.testkit.scalatest.FileMatchers.{digest => digestField, filename => filenameField, mediaType => mediaTypeField}
import ch.epfl.bluebrain.nexus.tests.HttpClient.acceptAll
import ch.epfl.bluebrain.nexus.tests.Identity.storages.Coyote
import ch.epfl.bluebrain.nexus.tests.Optics
import ch.epfl.bluebrain.nexus.tests.Optics.{error, filterMetadataKeys, location}
import ch.epfl.bluebrain.nexus.tests.config.S3Config
import ch.epfl.bluebrain.nexus.tests.iam.types.Permission
import ch.epfl.bluebrain.nexus.tests.kg.files.FilesAssertions.expectFileContent
import ch.epfl.bluebrain.nexus.tests.kg.files.S3StorageSpec.DelegationResponse
import ch.epfl.bluebrain.nexus.tests.kg.files.model.FileInput
import eu.timepit.refined.types.all.NonEmptyString
import fs2.Stream
import fs2.aws.s3.models.Models.{BucketName, FileKey}
import fs2.aws.s3.{AwsRequestModifier, S3}
import io.circe.generic.semiauto.deriveDecoder
import io.circe.{Decoder, Json}
import io.circe.jawn.parseByteBuffer
import io.circe.syntax.{EncoderOps, KeyOps}
import io.laserdisc.pure.s3.tagless.Interpreter
import org.apache.commons.codec.binary.Hex
import org.scalatest.Assertion
import software.amazon.awssdk.auth.credentials.{AnonymousCredentialsProvider, AwsBasicCredentials, StaticCredentialsProvider}
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3AsyncClient
import software.amazon.awssdk.services.s3.model._

import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.Paths
import java.security.MessageDigest
import java.util.Base64
import scala.jdk.CollectionConverters._

class S3StorageSpec extends StorageSpec {

  override def storageName: String = "s3"

  override def storageType: String = "S3Storage"

  override def storageId: String = "mys3storage"

  override def locationPrefix: Option[String] = Some(s3Config.prefix)

  val s3Config: S3Config = storageConfig.s3

  private val bucket                 = genId()
  private val logoFilename           = "nexus-logo.png"
  private val logoKey                = s"some/path/to/$logoFilename"
  private val logoSha256Base64Digest = "Bb9EKBAhO55f7NUkLu/v8fPSB5E4YclmWMdcz1iZfoc="
  private val logoSha256HexDigest    = Hex.encodeHexString(Base64.getDecoder.decode(logoSha256Base64Digest))

  val s3Endpoint: String       = "http://s3.localhost.localstack.cloud:4566"
  val s3BucketEndpoint: String = s"http://s3.localhost.localstack.cloud:4566/$bucket"

  private val credentialsProvider = (s3Config.accessKey, s3Config.secretKey) match {
    case (Some(ak), Some(sk)) => StaticCredentialsProvider.create(AwsBasicCredentials.create(ak, sk))
    case _                    => AnonymousCredentialsProvider.create()
  }

  private val s3Client = Interpreter[IO]
    .S3AsyncClientOpResource(
      S3AsyncClient
        .builder()
        .credentialsProvider(credentialsProvider)
        .endpointOverride(new URI(s3Endpoint))
        .forcePathStyle(true)
        .region(Region.US_EAST_1)
    )
    .allocated
    .map(_._1)
    .accepted

  private val s3 = S3.create(s3Client)

  override def beforeAll(): Unit = {
    super.beforeAll()
    (createBucket(bucket) >> uploadLogoFileToS3(logoKey)).accepted
    ()
  }

  private def createBucket(b: String): IO[CreateBucketResponse] =
    s3Client.createBucket(CreateBucketRequest.builder.bucket(b).build)

  private def uploadLogoFileToS3(key: String): IO[PutObjectResponse] = s3Client.putObject(
    PutObjectRequest.builder
      .bucket(bucket)
      .key(key)
      .checksumAlgorithm(ChecksumAlgorithm.SHA256)
      .checksumSHA256(logoSha256Base64Digest)
      .build,
    Paths.get(getClass.getResource("/kg/files/nexus-logo.png").toURI)
  )

  override def afterAll(): Unit = {
    val cleanup: IO[Unit] = for {
      resp   <- s3Client.listObjects(ListObjectsRequest.builder.bucket(bucket).build)
      objects = resp.contents.asScala.toList
      _      <- objects.traverse(obj => s3Client.deleteObject(DeleteObjectRequest.builder.bucket(bucket).key(obj.key).build))
      _      <- s3Client.deleteBucket(DeleteBucketRequest.builder.bucket(bucket).build)
    } yield ()

    cleanup.accepted

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
      ): _*
    )

  override def createStorages(projectRef: String, storId: String, storName: String): IO[Assertion] = {
    val payload = jsonContentOf(
      "kg/storages/s3.json",
      "storageId" -> s"https://bluebrain.github.io/nexus/vocabulary/$storId",
      "bucket"    -> bucket
    )

    val payload2 = jsonContentOf(
      "kg/storages/s3.json",
      "storageId"       -> s"https://bluebrain.github.io/nexus/vocabulary/${storId}2",
      "bucket"          -> bucket
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

  s"Linking in S3" should {
    "be rejected" in {
      val payload = Json.obj(
        "filename"  := "logo.png",
        "path"      := logoKey,
        "mediaType" := "image/png"
      )
      deltaClient.put[Json](s"/files/$projectRef/logo.png?storage=nxv:${storageId}2", payload, Coyote) {
        (_, response) =>
          response.status shouldEqual StatusCodes.BadRequest
      }
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
        locationValue should endWith(UrlUtils.encode(name))
        val decodeLocation = UrlUtils.decode(locationValue)
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

  private def linkedFileResponse(
      id: String,
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
      ): _*
    )

  private def uploadFileBytesToS3(content: Array[Byte], path: String): IO[String] = {
    val sha256Base64Encoded                  = new String(
      Base64.getEncoder.encode(MessageDigest.getInstance("SHA-256").digest(content)),
      StandardCharsets.UTF_8
    )
    val modifier: AwsRequestModifier.Upload1 = (b: PutObjectRequest.Builder) =>
      b.checksumAlgorithm(ChecksumAlgorithm.SHA256).checksumSHA256(sha256Base64Encoded)
    val uploadPipe                           =
      s3.uploadFile(BucketName(NonEmptyString.unsafeFrom(bucket)), FileKey(NonEmptyString.unsafeFrom(path)), modifier)
    Stream.fromIterator[IO](content.iterator, 16).through(uploadPipe).compile.drain.as(sha256Base64Encoded)
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
        _         <- uploadLogoFileToS3(path)
        json      <- createFileLinkNoId(storageId, payload)
        id         = Optics.`@id`.getOption(json).value
        encodedId  = UrlUtils.encode(id)
        assertion <- deltaClient.get[Json](s"/files/$projectRef/$encodedId", Coyote) { (json, response) =>
                       response.status shouldEqual StatusCodes.OK
                       filterMetadataKeys(json) shouldEqual linkedFileResponse(
                         id,
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
        _         <- uploadLogoFileToS3(path)
        _         <- createFileLink(id, storageId, payload)
        fullId     = s"$attachmentPrefix$id"
        assertion <- deltaClient.get[Json](s"/files/$projectRef/$id", Coyote) { (json, response) =>
                       response.status shouldEqual StatusCodes.OK
                       filterMetadataKeys(json) shouldEqual linkedFileResponse(
                         fullId,
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
        _         <- uploadLogoFileToS3(path)
        _         <- createFileLink(id, storageId, payload)
        assertion <- deltaClient.get[Json](s"/files/$projectRef/$id", Coyote) { (json, response) =>
                       response.status shouldEqual StatusCodes.OK
                       json should have(mediaTypeField("image/dan"))
                     }
      } yield assertion
    }

    "be updated" in {
      val id              = genId()
      val fileContent     = genString()
      val filename        = s"${genString()}.txt"
      val originalPath    = s"$id/nexus-logo.png"
      val updatedPath     = s"$id/some/path/$filename"
      val originalPayload = Json.obj("path" -> Json.fromString(originalPath))
      val updatedPayload  =
        Json.obj("path" -> Json.fromString(updatedPath), "mediaType" := "text/plain; charset=UTF-8")

      for {
        _             <- uploadLogoFileToS3(originalPath)
        _             <- createFileLink(id, storageId, originalPayload)
        s3Digest      <- uploadFileBytesToS3(fileContent.getBytes(StandardCharsets.UTF_8), updatedPath)
        _             <- updateFileLink(id, storageId, 1, updatedPayload)
        _             <- deltaClient.get[ByteString](s"/files/$projectRef/$id", Coyote, acceptAll) {
                           expectFileContent(
                             filename,
                             ContentTypes.`text/plain(UTF-8)`,
                             fileContent
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
      val content = {
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

  s"Delegate S3 file upload" should {
    "succeed using JWS protocol with flattened serialization" in {
      val filename               = genString()
      val (name, desc, keywords) = (genString(), genString(), Json.obj(genString() := genString()))
      val metadata               =
        json"""
          {
            "name": "$name",
            "description": "$desc",
            "keywords": $keywords
          }
            """
      val payload                =
        Json.obj("filename" -> Json.fromString(filename), "metadata" -> metadata, "mediaType" := "image/dan")

      for {
        jwsPayload                                  <-
          deltaClient
            .postAndReturn[Json](s"/delegate/files/$projectRef/validate?storage=nxv:$storageId", payload, Coyote) {
              expectOk
            }
        resp                                        <- parseDelegationResponse(jwsPayload)
        DelegationResponse(id, path, returnedBucket) = resp
        _                                            = returnedBucket shouldEqual bucket
        _                                           <- uploadLogoFileToS3(path)
        _                                           <- deltaClient.post[Json](s"/delegate/files/$projectRef?storage=nxv:$storageId", jwsPayload, Coyote) {
                                                         expectCreated
                                                       }
        encodedId                                    = UrlUtils.encode(id)
        filename                                     = path.split("/").last
        expectedMetadata                             = Json.obj("name" := name, "description" := desc, "_keywords" := keywords)
        assertion                                   <- deltaClient.get[Json](s"/files/$projectRef/$encodedId", Coyote) { (json, response) =>
                                                         response.status shouldEqual StatusCodes.OK
                                                         val expected = linkedFileResponse(
                                                           id,
                                                           logoSha256HexDigest,
                                                           location = path,
                                                           filename = filename,
                                                           mediaType = "image/dan"
                                                         ).deepMerge(expectedMetadata)
                                                         val actual   = filterMetadataKeys(json)
                                                         actual shouldEqual expected
                                                       }
      } yield assertion
    }
  }

  def parseDelegationResponse(jwsPayload: Json): IO[DelegationResponse] = IO.fromEither {
    for {
      encodedPayload <- jwsPayload.hcursor.get[String]("payload")
      decodedPayload  = Base64.getDecoder.decode(encodedPayload)
      jsonPayload    <- parseByteBuffer(ByteBuffer.wrap(decodedPayload))
      resp           <- jsonPayload.as[DelegationResponse]
    } yield resp
  }
}

object S3StorageSpec {

  final case class DelegationResponse(id: String, path: String, bucket: String)
  object DelegationResponse {
    implicit val dec: Decoder[DelegationResponse] = deriveDecoder
  }

}
