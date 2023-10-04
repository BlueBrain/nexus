package ch.epfl.bluebrain.nexus.storage.attributes

import akka.stream.Materializer
import akka.stream.scaladsl.{Keep, Sink}
import akka.util.ByteString
import cats.effect.Effect
import cats.implicits._
import ch.epfl.bluebrain.nexus.storage.File.{Digest, FileAttributes}
import ch.epfl.bluebrain.nexus.storage.StorageError.InternalError
import ch.epfl.bluebrain.nexus.storage._

import java.nio.file.{Files, Path}
import java.security.MessageDigest
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

trait AttributesComputation[F[_], Source] {

  /**
    * Given a path and an algorithm, generates its FileAttributes
    *
    * @param path
    *   the path to the file
    * @param algorithm
    *   the digest algorithm
    * @return
    *   a computed file attributes, wrapped on the effect type F
    */
  def apply(path: Path, algorithm: String): F[FileAttributes]
}

object AttributesComputation {

  private def sinkSize: Sink[ByteString, Future[Long]] = Sink.fold(0L)(_ + _.size)

  def sinkDigest(msgDigest: MessageDigest)(implicit ec: ExecutionContext): Sink[ByteString, Future[Digest]] =
    Sink
      .fold(msgDigest) { (digest, currentBytes: ByteString) =>
        digest.update(currentBytes.asByteBuffer)
        digest
      }
      .mapMaterializedValue(_.map(dig => Digest(dig.getAlgorithm, dig.digest().map("%02x".format(_)).mkString)))

  /**
    * A computation of attributes for a source of type AkkaSource
    *
    * @tparam F
    *   the effect type
    * @return
    *   a AttributesComputation implemented for a source of type AkkaSource
    */
  implicit def akkaAttributes[F[_]](implicit
      contentTypeDetector: ContentTypeDetector,
      ec: ExecutionContext,
      mt: Materializer,
      F: Effect[F]
  ): AttributesComputation[F, AkkaSource] =
    (path: Path, algorithm: String) => {
      if (!Files.exists(path)) F.raiseError(InternalError(s"Path not found '$path'"))
      else
        Try(MessageDigest.getInstance(algorithm)) match {
          case Success(msgDigest) =>
            val isDir  = Files.isDirectory(path)
            val source = if (isDir) folderSource(path) else fileSource(path)
            source
              .alsoToMat(sinkSize)(Keep.right)
              .toMat(sinkDigest(msgDigest)) { (bytesF, digestF) =>
                (bytesF, digestF).mapN { case (bytes, digest) =>
                  FileAttributes(path.toAkkaUri, bytes, digest, contentTypeDetector(path, isDir))
                }
              }
              .run()
              .to[F]
          case Failure(_)         => F.raiseError(InternalError(s"Invalid algorithm '$algorithm'."))
        }

    }
}
