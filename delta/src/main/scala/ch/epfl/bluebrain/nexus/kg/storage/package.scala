package ch.epfl.bluebrain.nexus.kg

import java.net.URI
import java.nio.file.{Path, Paths}
import java.security.MessageDigest
import java.util.UUID

import akka.http.scaladsl.model.Uri
import akka.stream.scaladsl.{Sink, Source}
import akka.util.ByteString
import ch.epfl.bluebrain.nexus.kg.resources.ProjectIdentifier.ProjectRef
import ch.epfl.bluebrain.nexus.kg.resources.file.File.Digest

import scala.concurrent.{ExecutionContext, Future}

package object storage {
  type AkkaSource = Source[ByteString, Any]

  /**
    * A sink that computes the digest of the input ByteString
    *
    * @param algorithm the digest algorithm. E.g.: SHA-256
    */
  def digestSink(algorithm: String)(implicit ec: ExecutionContext): Sink[ByteString, Future[Digest]] =
    Sink
      .fold(MessageDigest.getInstance(algorithm)) { (digest, currentBytes: ByteString) =>
        digest.update(currentBytes.asByteBuffer)
        digest
      }
      .mapMaterializedValue(_.map(dig => Digest(dig.getAlgorithm, dig.digest.map("%02x".format(_)).mkString)))

  /**
    * Converts an Akka [[akka.http.scaladsl.model.Uri]] in the form `file://...` to a [[java.nio.file.Path]].
    *
    * @param uri the Uri to convert
    * @return Some(path) if the input Uri was valid, None otherwise
    */
  def uriToPath(uri: Uri): Option[Path] =
    if (uri.scheme == "file") Some(Paths.get(URI.create(uri.toString)))
    else None

  /**
    * Converts a [[java.nio.file.Path]] to an Akka [[akka.http.scaladsl.model.Uri]].
    *
    * @param path the Path to convert
    * @return an Uri of the form file://$path
    */
  def pathToUri(path: Path): Uri = Uri(path.toUri.toString)

  /**
    * Builds a relative file path that is mangled in the form:
    * uuid = 12345678-90ab-cdef-abcd-1234567890ab
    * {project_id}/1/2/3/4/5/6/7/8/{filename}
    *
    * @param ref      the parent project reference
    * @param uuid     the file UUID
    * @param filename the filename
    * @return the mangled file path
    */
  def mangle(ref: ProjectRef, uuid: UUID, filename: String): String = {
    val lowercase = uuid.toString.toLowerCase
    s"${ref.id}/${lowercase.takeWhile(_ != '-').mkString("/")}/$filename"
  }
}
