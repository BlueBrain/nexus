package ch.epfl.bluebrain.nexus.tests.kg.files

import akka.http.scaladsl.model.StatusCodes
import cats.effect.IO
import cats.implicits.toTraverseOps
import ch.epfl.bluebrain.nexus.testkit.scalatest.FileMatchers.{mediaType => mediaTypeField}
import ch.epfl.bluebrain.nexus.tests.Identity.storages.Coyote
import ch.epfl.bluebrain.nexus.tests.Optics.{error, filterMetadataKeys}
import ch.epfl.bluebrain.nexus.tests.config.S3Config
import ch.epfl.bluebrain.nexus.tests.iam.types.Permission
import io.circe.Json
import io.circe.syntax.{EncoderOps, KeyOps}
import io.laserdisc.pure.s3.tagless.Interpreter
import org.apache.commons.codec.binary.Hex
import org.scalatest.Assertion
import software.amazon.awssdk.auth.credentials.{AnonymousCredentialsProvider, AwsBasicCredentials, StaticCredentialsProvider}
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3AsyncClient
import software.amazon.awssdk.services.s3.model._

import java.net.URI
import java.nio.file.Paths
import java.util.Base64
import scala.jdk.CollectionConverters._

class S3StorageSpec extends StorageSpec {

  override def storageName: String = "s3"

  override def storageType: String = "S3Storage"

  override def storageId: String = "mys3storage"

  override def locationPrefix: Option[String] = Some(s3BucketEndpoint)

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

  val s3Client = Interpreter[IO]
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

  override def beforeAll(): Unit = {
    super.beforeAll()
    (s3Client.createBucket(CreateBucketRequest.builder.bucket(bucket).build) >> uploadLogoFile(logoKey)).accepted
    ()
  }

  private def uploadLogoFile(key: String): IO[PutObjectResponse] = s3Client.putObject(
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

  private def storageResponse(project: String, id: String, readPermission: String, writePermission: String) =
    jsonContentOf(
      "kg/storages/s3-response.json",
      replacements(
        Coyote,
        "id"          -> id,
        "project"     -> project,
        "self"        -> storageSelf(project, s"https://bluebrain.github.io/nexus/vocabulary/$id"),
        "bucket"      -> bucket,
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

    val expectedStorage          = storageResponse(projectRef, storId, "resources/read", "files/write")
    val storageId2               = s"${storId}2"
    val expectedStorageWithPerms =
      storageResponse(projectRef, storageId2, "s3/read", "s3/write")

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
  }

  s"Linking in S3" should {
    "be rejected" in {
      val payload = Json.obj(
        "filename"  -> Json.fromString("logo.png"),
        "path"      -> Json.fromString(logoKey),
        "mediaType" -> Json.fromString("image/png")
      )
      deltaClient.put[Json](s"/files/$projectRef/logo.png?storage=nxv:${storageId}2", payload, Coyote) {
        (_, response) =>
          response.status shouldEqual StatusCodes.BadRequest
      }
    }
  }

  private def registrationResponse(id: String, digestValue: String, location: String): Json =
    jsonContentOf(
      "kg/files/registration-metadata.json",
      replacements(
        Coyote,
        "id"          -> id,
        "storageId"   -> storageId,
        "self"        -> fileSelf(projectRef, id),
        "projId"      -> s"$projectRef",
        "digestValue" -> digestValue,
        "location"    -> location
      ): _*
    )

  s"Registering an S3 file in-place" should {
    "succeed" in {
      val id      = genId()
      val path    = s"$id/nexus-logo.png"
      val payload = Json.obj("path" -> Json.fromString(path))

      for {
        _         <- uploadLogoFile(path)
        _         <- deltaClient.put[Json](s"/files/$projectRef/register/$id?storage=nxv:$storageId", payload, Coyote) {
                       (_, response) => response.status shouldEqual StatusCodes.Created
                     }
        fullId     = s"$attachmentPrefix$id"
        location   = s"$s3BucketEndpoint/$path"
        assertion <- deltaClient.get[Json](s"/files/$projectRef/$id", Coyote) { (json, response) =>
                       response.status shouldEqual StatusCodes.OK
                       filterMetadataKeys(json) shouldEqual registrationResponse(fullId, logoSha256HexDigest, location)
                     }
      } yield assertion
    }

    "succeed when a content type is supplied" in {
      val id      = genId()
      val path    = s"$id/nexus-logo.png"
      val payload = Json.obj("path" := path, "mediaType" := "image/dan")

      for {
        _         <- uploadLogoFile(path)
        _         <- deltaClient.put[Json](s"/files/$projectRef/register/$id?storage=nxv:$storageId", payload, Coyote) {
                       (_, response) => response.status shouldEqual StatusCodes.Created
                     }
        assertion <- deltaClient.get[Json](s"/files/$projectRef/$id", Coyote) { (json, response) =>
                       response.status shouldEqual StatusCodes.OK
                       json should have(mediaTypeField("image/dan"))
                     }
      } yield assertion
    }
  }
}
