package ch.epfl.bluebrain.nexus.tests.kg.files

import akka.http.scaladsl.model.StatusCodes
import cats.effect.IO
import ch.epfl.bluebrain.nexus.tests.Identity.storages.Coyote
import ch.epfl.bluebrain.nexus.tests.Optics.filterMetadataKeys
import ch.epfl.bluebrain.nexus.tests.config.S3Config
import ch.epfl.bluebrain.nexus.tests.iam.types.Permission
import io.circe.Json
import org.scalatest.Assertion
import software.amazon.awssdk.auth.credentials.{AnonymousCredentialsProvider, AwsBasicCredentials, StaticCredentialsProvider}
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model._

import java.net.URI
import java.nio.file.Paths
import scala.jdk.CollectionConverters._

class S3StorageSpec extends StorageSpec {

  override def storageName: String = "s3"

  override def storageType: String = "S3Storage"

  override def storageId: String = "mys3storage"

  override def locationPrefix: Option[String] = Some(s3BucketEndpoint)

  val s3Config: S3Config = storageConfig.s3

  private val bucket  = genId()
  private val logoKey = "some/path/to/nexus-logo.png"

  val s3Endpoint: String       = "http://s3.localhost.localstack.cloud:4566" // s"http://localhost:4566"
  val s3BucketEndpoint: String = s"http://$bucket.s3.localhost.localstack.cloud:4566" //s"http://localhost:4566/$bucket"

  private val credentialsProvider = (s3Config.accessKey, s3Config.secretKey) match {
    case (Some(ak), Some(sk)) => StaticCredentialsProvider.create(AwsBasicCredentials.create(ak, sk))
    case _                    => AnonymousCredentialsProvider.create()
  }

  private val s3Client = S3Client.builder
    .endpointOverride(new URI(s3Endpoint))//s"http://${sys.props.getOrElse("minio-url", "localhost:9000")}"))
    .credentialsProvider(credentialsProvider)
    .region(Region.US_EAST_1)
    .build

  override def beforeAll(): Unit = {
    super.beforeAll()
    // Configure minio
    s3Client.createBucket(CreateBucketRequest.builder.bucket(bucket).build)
    s3Client.putObject(
      PutObjectRequest.builder.bucket(bucket).key(logoKey).build,
      Paths.get(getClass.getResource("/kg/files/nexus-logo.png").toURI)
    )
    ()
  }

  override def afterAll(): Unit = {
    val objects = s3Client.listObjects(ListObjectsRequest.builder.bucket(bucket).build)
    objects.contents.asScala.foreach { obj =>
      s3Client.deleteObject(DeleteObjectRequest.builder.bucket(bucket).key(obj.key).build)
    }
    s3Client.deleteBucket(DeleteBucketRequest.builder.bucket(bucket).build)
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
        "endpoint"    -> s3Endpoint,
        "read"        -> readPermission,
        "write"       -> writePermission
      ): _*
    )

  override def createStorages(projectRef: String, storId: String, storName: String): IO[Assertion] = {
    val payload = jsonContentOf(
      "kg/storages/s3.json",
      "storageId" -> s"https://bluebrain.github.io/nexus/vocabulary/$storId",
      "bucket"    -> bucket,
      "endpoint"  -> s3Endpoint
    )

    val payload2 = jsonContentOf(
      "kg/storages/s3.json",
      "storageId"       -> s"https://bluebrain.github.io/nexus/vocabulary/${storId}2",
      "bucket"          -> bucket,
      "endpoint"        -> s3Endpoint
    ) deepMerge Json.obj(
      "region"          -> Json.fromString("eu-west-2"),
      "readPermission"  -> Json.fromString(s"$storName/read"),
      "writePermission" -> Json.fromString(s"$storName/write")
    )

    val expectedStorage          = storageResponse(projectRef, storId, "resources/read", "files/write")
    val storageId2               = s"${storId}2"
    val expectedStorageWithPerms =
      storageResponse(projectRef, storageId2, "s3/read", "s3/write")
        .deepMerge(Json.obj("region" -> Json.fromString("eu-west-2")))

    for {
      _ <- log("Creating first storage")
      _ <- storagesDsl.createStorage(payload, projectRef)
      _ <- log("Created storage")
      _ <- storagesDsl.checkStorageMetadata(projectRef, storId, expectedStorage)
      _ <- log("checked storage meta")
      _ <- permissionDsl.addPermissions(Permission(storName, "read"), Permission(storName, "write"))
      _ <- log("Added perms")
      _ <- storagesDsl.createStorage(payload2, projectRef)
      _ <- log("Created second storage")
      _ <- storagesDsl.checkStorageMetadata(projectRef, storageId2, expectedStorageWithPerms)
      _ <- log("Checked second storage meta")
    } yield succeed
  }

  def log(msg: String): IO[Unit] = IO.println(msg)

  "creating a s3 storage" should {
    "fail creating an S3Storage with an invalid bucket" in {
      val payload = jsonContentOf(
        "kg/storages/s3.json",
        "storageId" -> s"https://bluebrain.github.io/nexus/vocabulary/missing",
        "bucket"    -> "foobar",
        "endpoint"  -> s3Endpoint
      )

      deltaClient.post[Json](s"/storages/$projectRef", payload, Coyote) { (json, response) =>
        json shouldEqual jsonContentOf("kg/storages/s3-error.json")
        response.status shouldEqual StatusCodes.BadRequest
      }
    }
  }

  s"Linking in S3" should {
    "link an existing file" in {
      val payload = Json.obj(
        "filename"  -> Json.fromString("logo.png"),
        "path"      -> Json.fromString(logoKey),
        "mediaType" -> Json.fromString("image/png")
      )
      val fileId  = s"${config.deltaUri}/resources/$projectRef/_/logo.png"
      deltaClient.put[Json](s"/files/$projectRef/logo.png?storage=nxv:${storageId}2", payload, Coyote) {
        (json, response) =>
          response.status shouldEqual StatusCodes.Created
          filterMetadataKeys(json) shouldEqual
            jsonContentOf(
              "kg/files/linking-metadata.json",
              replacements(
                Coyote,
                "projId"         -> projectRef,
                "self"           -> fileSelf(projectRef, fileId),
                "endpoint"       -> s3Endpoint,
                "endpointBucket" -> s3BucketEndpoint,
                "key"            -> logoKey
              ): _*
            )
      }
    }
  }

  "fail to link a nonexistent file" in {
    val payload = Json.obj(
      "filename"  -> Json.fromString("logo.png"),
      "path"      -> Json.fromString("non/existent.png"),
      "mediaType" -> Json.fromString("image/png")
    )

    deltaClient.put[Json](s"/files/$projectRef/nonexistent.png?storage=nxv:${storageId}2", payload, Coyote) {
      (json, response) =>
        response.status shouldEqual StatusCodes.BadRequest
        json shouldEqual jsonContentOf(
          "kg/files/linking-notfound.json",
          "org"            -> orgId,
          "proj"           -> projId,
          "endpointBucket" -> s3BucketEndpoint
        )
    }
  }
}
