package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3

import akka.actor.ActorSystem
import akka.stream.alpakka.s3.scaladsl.S3
import akka.stream.alpakka.s3.{BucketAccess, S3Attributes}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StoragesConfig.StorageTypeConfig
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageValue.S3StorageValue
import ch.epfl.bluebrain.nexus.testkit.minio.MinioDocker
import monix.bio.{IO, Task}
import org.scalatest.{Suite, Suites}

import java.net.URLDecoder
import java.nio.charset.StandardCharsets.UTF_8

class MinioSpec extends Suites with MinioDocker {
  override val nestedSuites: IndexedSeq[Suite] = Vector(
    new S3StorageAccessSpec(this),
    new S3StorageSaveAndFetchFileSpec(this),
    new S3StorageLinkFileSpec(this)
  )
}

object MinioSpec {
  def createBucket(value: S3StorageValue)(implicit config: StorageTypeConfig, system: ActorSystem): Task[Unit] = {
    implicit val attributes = S3Attributes.settings(value.alpakkaSettings(config))

    IO.deferFuture(S3.checkIfBucketExists(value.bucket)).flatMap {
      case BucketAccess.NotExists => Task.delay(S3.makeBucket(value.bucket)) >> Task.unit
      case _                      => Task.unit
    }
  }

  def deleteBucket(value: S3StorageValue)(implicit config: StorageTypeConfig, system: ActorSystem): Task[Unit] = {
    implicit val attributes = S3Attributes.settings(value.alpakkaSettings(config))

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
