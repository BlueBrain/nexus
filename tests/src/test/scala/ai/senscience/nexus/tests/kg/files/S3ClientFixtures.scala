package ai.senscience.nexus.tests.kg.files

import ai.senscience.nexus.tests.config.ConfigLoader.*
import ai.senscience.nexus.tests.config.StorageConfig
import cats.effect.IO
import cats.syntax.all.*
import com.typesafe.config.ConfigFactory
import eu.timepit.refined.types.all.NonEmptyString
import fs2.Stream
import fs2.aws.s3.models.Models.{BucketName, FileKey}
import fs2.aws.s3.{AwsRequestModifier, S3}
import io.laserdisc.pure.s3.tagless.{Interpreter, S3AsyncClientOp}
import org.apache.commons.codec.binary.Hex
import software.amazon.awssdk.auth.credentials.{AnonymousCredentialsProvider, AwsBasicCredentials, StaticCredentialsProvider}
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3AsyncClient
import software.amazon.awssdk.services.s3.model.*

import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Paths
import java.security.MessageDigest
import java.util.Base64
import scala.jdk.CollectionConverters.*

trait S3ClientFixtures {

  private val s3Config = load[StorageConfig](ConfigFactory.load(), "storage").s3

  private val s3Endpoint: String = "http://s3.localhost.localstack.cloud:4566"

  val logoFilename: String           = "nexus-logo.png"
  val logoKey: String                = s"some/path/to/$logoFilename"
  val logoSha256Base64Digest: String = "Bb9EKBAhO55f7NUkLu/v8fPSB5E4YclmWMdcz1iZfoc="
  val logoSha256HexDigest: String    = Hex.encodeHexString(Base64.getDecoder.decode(logoSha256Base64Digest))

  def createS3Client: IO[S3AsyncClientOp[IO]] = {
    val credentialsProvider = (s3Config.accessKey, s3Config.secretKey) match {
      case (Some(ak), Some(sk)) => StaticCredentialsProvider.create(AwsBasicCredentials.create(ak, sk))
      case _                    => AnonymousCredentialsProvider.create()
    }
    Interpreter[IO]
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
  }

  def createBucket(bucketName: String)(implicit s3Client: S3AsyncClientOp[IO]): IO[Unit] =
    s3Client.createBucket(CreateBucketRequest.builder.bucket(bucketName).build).void

  def putFile(bucket: String, key: String, content: String)(implicit s3Client: S3AsyncClientOp[IO]): IO[String] = {
    val s3                                   = S3.create(s3Client)
    val charset                              = StandardCharsets.UTF_8
    val contentBytes                         = content.getBytes(charset)
    val sha256Base64Encoded                  =
      new String(Base64.getEncoder.encode(MessageDigest.getInstance("SHA-256").digest(contentBytes)), charset)
    val modifier: AwsRequestModifier.Upload1 = (b: PutObjectRequest.Builder) =>
      b.checksumAlgorithm(ChecksumAlgorithm.SHA256).checksumSHA256(sha256Base64Encoded)
    val uploadPipe                           =
      s3.uploadFile(BucketName(NonEmptyString.unsafeFrom(bucket)), FileKey(NonEmptyString.unsafeFrom(key)), modifier)
    Stream.fromIterator[IO](contentBytes.iterator, 16).through(uploadPipe).compile.drain.as(sha256Base64Encoded)
  }

  def uploadLogoFileToS3(bucket: String, key: String)(implicit s3Client: S3AsyncClientOp[IO]): IO[PutObjectResponse] =
    s3Client.putObject(
      PutObjectRequest.builder
        .bucket(bucket)
        .key(key)
        .checksumAlgorithm(ChecksumAlgorithm.SHA256)
        .checksumSHA256(logoSha256Base64Digest)
        .build,
      Paths.get(getClass.getResource("/kg/files/nexus-logo.png").toURI)
    )

  def cleanupBucket(bucket: String)(implicit s3Client: S3AsyncClientOp[IO]): IO[Unit] =
    for {
      resp   <- s3Client.listObjects(ListObjectsRequest.builder.bucket(bucket).build)
      objects = resp.contents.asScala.toList
      _      <- objects.traverse(obj => s3Client.deleteObject(DeleteObjectRequest.builder.bucket(bucket).key(obj.key).build))
      _      <- s3Client.deleteBucket(DeleteBucketRequest.builder.bucket(bucket).build)
    } yield ()

}
