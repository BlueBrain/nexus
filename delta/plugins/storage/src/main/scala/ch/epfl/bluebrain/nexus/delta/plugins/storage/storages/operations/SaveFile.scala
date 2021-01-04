package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations

import akka.http.scaladsl.model.Uri
import akka.stream.scaladsl.Sink
import akka.util.ByteString
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.Digest.ComputedDigest
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.{FileAttributes, FileDescription}
import ch.epfl.bluebrain.nexus.delta.sdk.AkkaSource
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.DigestAlgorithm
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.StorageFileRejection.SaveFileRejection
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import monix.bio.IO

import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}

trait SaveFile {

  /**
    * Saves a file with the passed ''description'' and ''source''.
    *
    * @param description the file description
    * @param source      the file stream
    */
  def apply(description: FileDescription, source: AkkaSource): IO[SaveFileRejection, FileAttributes]
}

object SaveFile {

  /**
    * A sink that computes the digest of the input ByteString
    *
    * @param algorithm the digest algorithm. E.g.: SHA-256
    */
  def digestSink(algorithm: DigestAlgorithm)(implicit ec: ExecutionContext): Sink[ByteString, Future[ComputedDigest]] =
    Sink
      .fold(algorithm.digest) { (digest, currentBytes: ByteString) =>
        digest.update(currentBytes.asByteBuffer)
        digest
      }
      .mapMaterializedValue(_.map(dig => ComputedDigest(algorithm, dig.digest.map("%02x".format(_)).mkString)))

  /**
    * A sink that computes the size of the input ByteString
    */
  val sizeSink: Sink[ByteString, Future[Long]] =
    Sink.fold(0L) { (size, currentBytes: ByteString) =>
      size + currentBytes.size
    }

  /**
    * Builds a relative file path with intermediate folders taken from the passed ''uuid''
    *
    * Example:
    * uuid = 12345678-90ab-cdef-abcd-1234567890ab
    * {org}/{proj}/1/2/3/4/5/6/7/8/{filename}
    */
  def intermediateFolders(ref: ProjectRef, uuid: UUID, filename: String): Uri.Path =
    Uri.Path(s"$ref/${uuid.toString.toLowerCase.takeWhile(_ != '-').mkString("/")}/$filename")
}
