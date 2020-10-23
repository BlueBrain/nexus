package ch.epfl.bluebrain.nexus.storage.attributes

import java.nio.file.{Files, Path}
import java.security.MessageDigest

import akka.http.scaladsl.model.HttpCharsets.`UTF-8`
import akka.http.scaladsl.model.MediaTypes.{`application/octet-stream`, `application/x-tar`}
import akka.http.scaladsl.model.{ContentType, MediaType, MediaTypes}
import akka.stream.Materializer
import akka.stream.scaladsl.{Keep, Sink}
import akka.util.ByteString
import cats.effect.Effect
import cats.implicits._
import ch.epfl.bluebrain.nexus.storage.File.{Digest, FileAttributes}
import ch.epfl.bluebrain.nexus.storage.StorageError.InternalError
import ch.epfl.bluebrain.nexus.storage._
import org.apache.commons.io.FilenameUtils

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

trait AttributesComputation[F[_], Source] {

  /**
    * Given a path and an algorithm, generates its FileAttributes
    *
    * @param path      the path to the file
    * @param algorithm the digest algorithm
    * @return a computed file attributes, wrapped on the effect type F
    */
  def apply(path: Path, algorithm: String): F[FileAttributes]
}

object AttributesComputation {

  /**
    * Detects the media type of the provided path, based on the file system detector available for a certain path or on the path extension.
    * If the path is a directory, a application/x-tar content-type is returned
    *
    * @param path  the path
    * @param isDir flag to decide whether or not the path is a directory
    */
  def detectMediaType(path: Path, isDir: Boolean = false): ContentType =
    if (isDir) {
      `application/x-tar`
    } else {
      lazy val fromExtension   = Try(MediaTypes.forExtension(FilenameUtils.getExtension(path.toFile.getName)))
        .getOrElse(`application/octet-stream`)
      val mediaType: MediaType = Try(Files.probeContentType(path)) match {
        case Success(value) if value != null && value.nonEmpty => MediaType.parse(value).getOrElse(fromExtension)
        case _                                                 => fromExtension
      }
      ContentType(mediaType, () => `UTF-8`)
    }
  private def sinkSize: Sink[ByteString, Future[Long]]                 = Sink.fold(0L)(_ + _.size)

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
    * @tparam F the effect type
    * @return a AttributesComputation implemented for a source of type AkkaSource
    */
  implicit def akkaAttributes[F[_]](implicit
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
                  FileAttributes(path.toAkkaUri, bytes, digest, detectMediaType(path, isDir))
                }
              }
              .run()
              .to[F]
          case Failure(_)         => F.raiseError(InternalError(s"Invalid algorithm '$algorithm'."))
        }

    }
}
