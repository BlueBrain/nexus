package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3

import akka.actor.ActorSystem
import akka.stream.alpakka.s3.scaladsl.S3
import akka.stream.alpakka.s3.{BucketAccess, S3Attributes}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageValue.S3StorageValue
import com.whisk.docker.scalatest.DockerTestKit
import monix.bio.{IO, Task}
import org.scalatest.Suites

import java.net.URLDecoder
import java.nio.charset.StandardCharsets.UTF_8

class MinioSpec
    extends Suites(new S3StorageAccessSpec, new S3StorageSaveAndFetchFileSpec)
    with DockerTestKit
    with MinioDocker

object MinioSpec {
  def createBucket(value: S3StorageValue)(implicit system: ActorSystem): Task[Unit] = {
    implicit val attributes = S3Attributes.settings(value.toAlpakkaSettings)

    IO.deferFuture(S3.checkIfBucketExists(value.bucket)).flatMap {
      case BucketAccess.NotExists => Task.delay(S3.makeBucket(value.bucket)) >> Task.unit
      case _                      => Task.unit
    }
  }

  def deleteBucket(value: S3StorageValue)(implicit system: ActorSystem): Task[Unit] = {
    implicit val attributes = S3Attributes.settings(value.toAlpakkaSettings)

    IO.deferFuture(
      S3.listBucket(value.bucket, None)
        .withAttributes(attributes)
        .flatMapConcat { content =>
          S3.deleteObject(value.bucket, URLDecoder.decode(content.getKey, UTF_8.toString))
            .withAttributes(attributes)
        }
        .run()
    ) >> IO.deferFuture(S3.deleteBucket(value.bucket)) >> Task.unit
  }
}
