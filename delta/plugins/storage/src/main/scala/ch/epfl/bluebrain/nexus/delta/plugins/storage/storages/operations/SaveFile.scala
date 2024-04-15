package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations

import akka.actor.ActorSystem
import akka.http.scaladsl.model.BodyPartEntity
import akka.stream.scaladsl.Sink
import akka.util.ByteString
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.Digest.ComputedDigest
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileStorageMetadata
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.{DigestAlgorithm, Storage}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.disk.DiskStorageSaveFile
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote.RemoteDiskStorageSaveFile
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote.client.RemoteDiskStorageClient
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3.S3StorageSaveFile
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3.client.S3StorageClient
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef

import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}

trait SaveFile {

  /**
    * Saves a file with the passed ''description'' and ''source''.
    *
    * @param description
    *   the file description
    * @param entity
    *   the entity with the file content
    */
  def apply(storage: Storage, filename: String, entity: BodyPartEntity): IO[FileStorageMetadata]
}

object SaveFile {

  /**
    * Construct a [[SaveFile]] from the given ''storage''.
    */
  def apply(client: RemoteDiskStorageClient, s3Client: S3StorageClient)(implicit
      as: ActorSystem,
      uuidf: UUIDF
  ): SaveFile = new SaveFile {
    private val disk   = new DiskStorageSaveFile
    private val s3     = new S3StorageSaveFile(s3Client)
    private val remote = new RemoteDiskStorageSaveFile(client)

    override def apply(storage: Storage, filename: String, entity: BodyPartEntity): IO[FileStorageMetadata] =
      storage match {
        case storage: Storage.DiskStorage       => disk.apply(storage, filename, entity)
        case storage: Storage.S3Storage         => s3.apply(storage, filename, entity)
        case storage: Storage.RemoteDiskStorage => remote.apply(storage, filename, entity)
      }
  }

  /**
    * A sink that computes the digest of the input ByteString
    *
    * @param algorithm
    *   the digest algorithm. E.g.: SHA-256
    */
  def digestSink(algorithm: DigestAlgorithm)(implicit ec: ExecutionContext): Sink[ByteString, Future[ComputedDigest]] =
    Sink
      .fold(algorithm.digest) { (digest, currentBytes: ByteString) =>
        digest.update(currentBytes.asByteBuffer)
        digest
      }
      .mapMaterializedValue(_.map(dig => ComputedDigest(algorithm, dig.digest.map("%02x".format(_)).mkString)))

  /**
    * Builds a relative file path with intermediate folders taken from the passed ''uuid''
    *
    * Example: uuid = 12345678-90ab-cdef-abcd-1234567890ab {org}/{proj}/1/2/3/4/5/6/7/8/{filename}
    */
  def intermediateFolders(ref: ProjectRef, uuid: UUID, filename: String): String =
    s"$ref/${uuid.toString.toLowerCase.takeWhile(_ != '-').mkString("/")}/$filename"
}
