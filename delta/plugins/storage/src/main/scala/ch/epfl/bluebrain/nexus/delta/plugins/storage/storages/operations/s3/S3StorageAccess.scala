package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3

import akka.actor.ActorSystem
import akka.stream.alpakka.s3.S3Attributes
import akka.stream.alpakka.s3.scaladsl.S3
import akka.stream.scaladsl.Sink
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageRejection.StorageNotAccessible
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageValue.S3StorageValue
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.StorageAccess
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import monix.bio.Cause.{Error, Termination}
import monix.bio.IO

import java.util.NoSuchElementException

final class S3StorageAccess(implicit as: ActorSystem) extends StorageAccess {
  override type Storage = S3StorageValue

  override def apply(id: Iri, storage: S3StorageValue): IO[StorageNotAccessible, Unit] = {
    val attributes = S3Attributes.settings(storage.toAlpakkaSettings)

    IO.deferFuture(
      S3.listBucket(storage.bucket, None)
        .withAttributes(attributes)
        .runWith(Sink.head)
    ).redeemCauseWith(
      {
        case Error(_: NoSuchElementException) | Termination(_: NoSuchElementException) => IO.unit // // bucket is empty
        case err                                                                       => IO.raiseError(StorageNotAccessible(id, err.toThrowable.getMessage))
      },
      _ => IO.unit
    )
  }
}
