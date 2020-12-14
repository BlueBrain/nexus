package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.disk

import akka.actor.ActorSystem
import akka.http.scaladsl.model.Uri
import akka.stream.scaladsl.{FileIO, Keep, Sink}
import akka.util.ByteString
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.Digest.ComputedDigest
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.{FileAttributes, FileDescription}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.AkkaSource
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.DigestAlgorithm
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.Storage.DiskStorage
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageRejection.SaveFileRejection
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageRejection.SaveFileRejection.{CouldNotCreateDirectory, UnexpectedFileLocation, UnexpectedIOError}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.SaveFile
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import monix.bio.IO

import java.nio.file.StandardOpenOption._
import java.nio.file.{Files, Path, Paths}
import java.util.UUID
import scala.concurrent.Future
import scala.util.Try

class DiskStorageSaveFile(storage: DiskStorage)(implicit as: ActorSystem) extends SaveFile {

  import as.dispatcher

  override def apply(description: FileDescription, source: AkkaSource): IO[SaveFileRejection, FileAttributes] =
    initLocation(description.uuid, description.filename).flatMap { case (fullPath, relativePath) =>
      IO.fromFuture(
        source
          .alsoToMat(digestSink(storage.value.algorithm))(Keep.right)
          .toMat(FileIO.toPath(fullPath, Set(CREATE_NEW, WRITE))) { case (digFuture, ioFuture) =>
            digFuture.zipWith(ioFuture) {
              case (digest, io) if fullPath.toFile.exists() =>
                val location = Uri(fullPath.toUri.toString)
                Future.successful(
                  FileAttributes(
                    description.uuid,
                    location,
                    relativePath,
                    description.filename,
                    description.mediaType,
                    io.count,
                    digest
                  )
                )
              case _                                        =>
                Future.failed(new IllegalArgumentException())
            }
          }
          .run()
          .flatten
      ).leftMap(err => UnexpectedIOError(storage.id, description.filename, err.getMessage))
    }

  private def initLocation(uuid: UUID, filename: String): IO[SaveFileRejection, (Path, Uri.Path)] = {
    val relativeUriPath = intermediateFolders(storage.project, uuid, filename)
    IO.fromEither(for {
      relative <- Try(Paths.get(relativeUriPath.toString)).toEither.leftMap(wrongPath(filename, relativeUriPath, _))
      resolved <- Try(storage.value.volume.resolve(relative)).toEither.leftMap(wrongPath(filename, relativeUriPath, _))
      dir       = resolved.getParent
      _        <- Try(Files.createDirectories(dir)).toEither.leftMap(couldNotCreateDirectory(filename, dir, _))
    } yield resolved -> relativeUriPath)
  }

  /**
    * A sink that computes the digest of the input ByteString
    *
    * @param algorithm the digest algorithm. E.g.: SHA-256
    */
  private def digestSink(algorithm: DigestAlgorithm): Sink[ByteString, Future[ComputedDigest]] =
    Sink
      .fold(algorithm.digest) { (digest, currentBytes: ByteString) =>
        digest.update(currentBytes.asByteBuffer)
        digest
      }
      .mapMaterializedValue(_.map(dig => ComputedDigest(algorithm, dig.digest.map("%02x".format(_)).mkString)))

  /**
    * Builds a relative file path with intermediate folders taken from the passed ''uuid''
    *
    * Example:
    * uuid = 12345678-90ab-cdef-abcd-1234567890ab
    * {org}/{proj}/1/2/3/4/5/6/7/8/{filename}
    */
  private def intermediateFolders(ref: ProjectRef, uuid: UUID, filename: String): Uri.Path =
    Uri.Path(s"$ref/${uuid.toString.toLowerCase.takeWhile(_ != '-').mkString("/")}/$filename")

  private def wrongPath(filename: String, relativeUriPath: Uri.Path, err: Throwable) = {
    val errMsg = s"Couldn't create a Path due to '${err.getMessage}'."
    UnexpectedFileLocation(storage.id, filename, relativeUriPath, errMsg)
  }

  private def couldNotCreateDirectory(filename: String, directory: Path, err: Throwable) = {
    val errMsg = s"Couldn't create a directory '$directory' due to '${err.getMessage}'."
    CouldNotCreateDirectory(storage.id, filename, directory, errMsg)
  }

}
