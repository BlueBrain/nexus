package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3.client

import akka.http.scaladsl.model.Uri
import cats.effect.{IO, Ref, Resource}
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StoragesConfig.S3StorageConfig
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.DigestAlgorithm
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3.client.S3StorageClient.UploadMetadata
import ch.epfl.bluebrain.nexus.delta.rdf.syntax.uriSyntax
import ch.epfl.bluebrain.nexus.delta.sdk.error.ServiceError.FeatureDisabled
import fs2.aws.s3.S3
import fs2.aws.s3.models.Models.{BucketName, FileKey}
import fs2.{Chunk, Pipe, Stream}
import io.laserdisc.pure.s3.tagless.{Interpreter, S3AsyncClientOp}
import org.apache.commons.codec.binary.Hex
import software.amazon.awssdk.auth.credentials.{AwsBasicCredentials, AwsCredentialsProvider, StaticCredentialsProvider}
import software.amazon.awssdk.core.async.AsyncRequestBody
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3AsyncClient
import software.amazon.awssdk.services.s3.model._

import java.net.URI
import java.util.Base64

trait S3StorageClient {
  def listObjectsV2(bucket: String): IO[ListObjectsV2Response]

  def listObjectsV2(bucket: BucketName, prefix: String): IO[ListObjectsV2Response]

  final def readFile(bucket: String, fileKey: String): Stream[IO, Byte] =
    (Stream.eval(parseBucket(bucket)), Stream.eval(parseFileKey(fileKey))).flatMapN(readFile)

  def readFile(bucket: BucketName, fileKey: FileKey): Stream[IO, Byte]

  def headObject(bucket: String, key: String): IO[HeadObjectResponse]

  def copyObject(
      sourceBucket: BucketName,
      sourceKey: FileKey,
      destinationBucket: BucketName,
      destinationKey: FileKey
  ): IO[CopyObjectResponse]

  def uploadFile(
      fileData: Stream[IO, Byte],
      bucket: String,
      key: String,
      algorithm: DigestAlgorithm
  ): IO[UploadMetadata]

  def objectExists(bucket: String, key: String): IO[Boolean]

  def baseEndpoint: Uri
}

object S3StorageClient {

  case class UploadMetadata(checksum: String, fileSize: Long, location: Uri)

  def resource(s3Config: Option[S3StorageConfig]): Resource[IO, S3StorageClient] = s3Config match {
    case Some(cfg) =>
      val creds =
        StaticCredentialsProvider.create(
          AwsBasicCredentials.create(cfg.defaultAccessKey.value, cfg.defaultSecretKey.value)
        )
      resource(URI.create(cfg.defaultEndpoint.toString()), creds)

    case None => Resource.pure(S3StorageClientDisabled)
  }

  def resource(endpoint: URI, credentialProvider: AwsCredentialsProvider): Resource[IO, S3StorageClient] =
    Interpreter[IO]
      .S3AsyncClientOpResource(
        S3AsyncClient
          .builder()
          .credentialsProvider(credentialProvider)
          .endpointOverride(endpoint)
          .forcePathStyle(true)
          .region(Region.US_EAST_1)
      )
      .map(new S3StorageClientImpl(_, endpoint.toString))

  final class S3StorageClientImpl(client: S3AsyncClientOp[IO], val baseEndpoint: Uri) extends S3StorageClient {
    private val s3: S3[IO] = S3.create(client)

    override def listObjectsV2(bucket: String): IO[ListObjectsV2Response] =
      client.listObjectsV2(ListObjectsV2Request.builder().bucket(bucket).build())

    override def listObjectsV2(bucket: BucketName, prefix: String): IO[ListObjectsV2Response] =
      client.listObjectsV2(ListObjectsV2Request.builder().bucket(bucket.value.value).prefix(prefix).build())

    override def readFile(bucket: BucketName, fileKey: FileKey): Stream[IO, Byte] =
      s3.readFile(bucket, fileKey)

    override def headObject(bucket: String, key: String): IO[HeadObjectResponse] =
      client.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).checksumMode(ChecksumMode.ENABLED).build)

    override def copyObject(
        sourceBucket: BucketName,
        sourceKey: FileKey,
        destinationBucket: BucketName,
        destinationKey: FileKey
    ): IO[CopyObjectResponse] =
      client.copyObject(
        CopyObjectRequest
          .builder()
          .sourceBucket(sourceBucket.value.value)
          .sourceKey(sourceKey.value.value)
          .destinationBucket(destinationBucket.value.value)
          .destinationKey(destinationKey.value.value)
          .checksumAlgorithm(ChecksumAlgorithm.SHA256) // TODO: See what to do with this
          .build()
      )

    override def objectExists(bucket: String, key: String): IO[Boolean] = {
      headObject(bucket, key)
        .redeemWith(
          {
            case _: NoSuchKeyException => IO.pure(false)
            case e                     => IO.raiseError(e)
          },
          _ => IO.pure(true)
        )
    }

    override def uploadFile(
        fileData: Stream[IO, Byte],
        bucket: String,
        key: String,
        algorithm: DigestAlgorithm
    ): IO[UploadMetadata] = {
      for {
        fileSizeAcc <- Ref.of[IO, Long](0L)
        digest      <- fileData
                         .evalTap(_ => fileSizeAcc.update(_ + 1))
                         .through(
                           uploadFilePipe(bucket, key, algorithm)
                         )
                         .compile
                         .onlyOrError
        fileSize    <- fileSizeAcc.get
        location     = baseEndpoint / bucket / Uri.Path(key)
      } yield UploadMetadata(digest, fileSize, location)
    }

    private def uploadFilePipe(bucket: String, key: String, algorithm: DigestAlgorithm): Pipe[IO, Byte, String] = {
      in =>
        fs2.Stream.eval {
          in.compile.to(Chunk).flatMap { chunks =>
            val bs = chunks.toByteBuffer
            for {
              response <- client.putObject(
                            PutObjectRequest
                              .builder()
                              .bucket(bucket)
                              .deltaDigest(algorithm)
                              .key(key)
                              .build(),
                            AsyncRequestBody.fromByteBuffer(bs)
                          )
            } yield {
              checksumFromResponse(response, algorithm)
            }
          }
        }
    }

    private def checksumFromResponse(response: PutObjectResponse, algorithm: DigestAlgorithm): String = {
      algorithm.value match {
        case "SHA-256" => Hex.encodeHexString(Base64.getDecoder.decode(response.checksumSHA256()))
        case "SHA-1"   => Hex.encodeHexString(Base64.getDecoder.decode(response.checksumSHA1()))
        case _         => throw new IllegalArgumentException(s"Unsupported algorithm for S3: ${algorithm.value}")
      }
    }

    implicit class PutObjectRequestOps(request: PutObjectRequest.Builder) {
      def deltaDigest(algorithm: DigestAlgorithm): PutObjectRequest.Builder =
        algorithm.value match {
          case "SHA-256" => request.checksumAlgorithm(ChecksumAlgorithm.SHA256)
          case "SHA-1"   => request.checksumAlgorithm(ChecksumAlgorithm.SHA1)
          case _         => throw new IllegalArgumentException(s"Unsupported algorithm for S3: ${algorithm.value}")
        }
    }
  }

  final case object S3StorageClientDisabled extends S3StorageClient {
    private val disabledErr      = FeatureDisabled("S3 storage is disabled")
    private val raiseDisabledErr = IO.raiseError(disabledErr)

    override def listObjectsV2(bucket: String): IO[ListObjectsV2Response] = raiseDisabledErr

    override def listObjectsV2(bucket: BucketName, prefix: String): IO[ListObjectsV2Response] = raiseDisabledErr

    override def readFile(bucket: BucketName, fileKey: FileKey): Stream[IO, Byte] = Stream.raiseError[IO](disabledErr)

    override def headObject(bucket: String, key: String): IO[HeadObjectResponse] = raiseDisabledErr

    override def baseEndpoint: Uri = throw disabledErr

    override def copyObject(
        sourceBucket: BucketName,
        sourceKey: FileKey,
        destinationBucket: BucketName,
        destinationKey: FileKey
    ): IO[CopyObjectResponse] = raiseDisabledErr

    override def objectExists(bucket: String, key: String): IO[Boolean] = raiseDisabledErr

    override def uploadFile(
        fileData: Stream[IO, Byte],
        bucket: String,
        key: String,
        algorithm: DigestAlgorithm
    ): IO[UploadMetadata] = raiseDisabledErr
  }
}
