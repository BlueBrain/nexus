package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3

import akka.actor.ActorSystem
import akka.stream.alpakka.s3.S3Attributes
import akka.stream.alpakka.s3.scaladsl.S3
import akka.stream.scaladsl.Sink
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StoragesConfig.StorageTypeConfig
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageRejection.StorageNotAccessible
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageValue.S3StorageValue
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.StorageAccess
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri

final class S3StorageAccess(config: StorageTypeConfig)(implicit as: ActorSystem) extends StorageAccess {
  override type Storage = S3StorageValue

  override def apply(id: Iri, storage: S3StorageValue): IO[Unit] = {
    val attributes = S3Attributes.settings(storage.alpakkaSettings(config))

    (IO.println(s"Listing contents of bucket ${storage.bucket} with attributes $attributes") >>
    IO.fromFuture(
      IO.delay(
        S3.listBucket(storage.bucket, None)
          .withAttributes(attributes)
          .runWith(Sink.head)
      )
    ) <* IO.println(s"Did the listing bucket")).redeemWith(
      {
        case _: NoSuchElementException => IO.unit // // bucket is empty
        case err                       =>
          IO.raiseError(StorageNotAccessible(id, err.getMessage))
      },
      _ => IO.unit
    )
  }
}
