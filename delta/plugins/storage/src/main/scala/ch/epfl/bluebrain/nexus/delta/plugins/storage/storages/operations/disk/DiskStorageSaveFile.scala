package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.disk

import cats.effect.IO
import cats.syntax.all.*
import ch.epfl.bluebrain.nexus.delta.kernel.Hex
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.Digest.ComputedDigest
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileAttributes.FileAttributesOrigin.Client
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.{Digest, FileStorageMetadata}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.{AbsolutePath, DigestAlgorithm}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.FileOperations.intermediateFolders
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.StorageFileRejection.SaveFileRejection.*
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.UploadingFile.DiskUploadingFile
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.disk.DiskStorageSaveFile.{fs2PathToUriPath, initLocation}
import ch.epfl.bluebrain.nexus.delta.sdk.FileData
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import fs2.hashing.{Hasher, Hashing}
import fs2.io.file.{Files, Flag, Flags, Path, WriteCursor}
import fs2.{Chunk, Pipe, Pull, Stream}
import org.http4s.Uri
import org.http4s.Uri.Path.Segment

import java.nio.ByteBuffer
import java.nio.file.FileAlreadyExistsException
import java.util.UUID

final class DiskStorageSaveFile(implicit uuidf: UUIDF) {

  private val flags: Flags = Flags(List(Flag.CreateNew, Flag.Write))

  def apply(uploading: DiskUploadingFile): IO[FileStorageMetadata] = {
    for {
      uuid                     <- uuidf()
      (fullPath, relativePath) <- initLocation(uploading, uuid)
      (size, digest)           <- storeFile(uploading.data, uploading.algorithm, fullPath)
      location                 <- IO.fromEither(Uri.fromString(fullPath.toNioPath.toUri.toString))
    } yield FileStorageMetadata(
      uuid = uuid,
      bytes = size,
      digest = digest,
      origin = Client,
      location = location,
      path = fs2PathToUriPath(relativePath)
    )
  }

  private def storeFile(data: FileData, algorithm: DigestAlgorithm, fullPath: Path): IO[(Long, Digest)] =
    data.through(store(algorithm, fullPath)).compile.lastOrError.adaptError {
      case _: FileAlreadyExistsException => ResourceAlreadyExists(fullPath.toString)
      case err                           => UnexpectedSaveError(fullPath.toString, err.getMessage)
    }

  // Stores the file while computing the hash and the file size
  private def store(algorithm: DigestAlgorithm, fullPath: Path): Pipe[IO, ByteBuffer, (Long, ComputedDigest)] = {
    def go(hasher: Hasher[IO], cursor: WriteCursor[IO], stream: FileData): Pull[IO, (Long, ComputedDigest), Unit] = {
      stream.pull.uncons.flatMap {
        case Some((buffers, tail)) =>
          Pull
            .eval(
              buffers.foldLeftM(cursor) { (accCursor, buffer) =>
                val chunk = Chunk.byteBuffer(buffer)
                hasher.update(chunk) >> accCursor.write(chunk)
              }
            )
            .flatMap { nc =>
              go(hasher, nc, tail)
            }
        case None                  =>
          Pull.eval(hasher.hash).flatMap { hash =>
            val digest = ComputedDigest(algorithm, Hex.valueOf(hash.bytes.toArray))
            Pull.eval(cursor.file.size).flatMap { size =>
              Pull.output1((size, digest))
            } >> Pull.done
          }
      }
    }
    data =>
      for {
        hasher <- Stream.resource(Hashing[IO].hasher(algorithm.asFs2))
        cursor <- Stream.resource(Files[IO].writeCursor(fullPath, flags))
        result <- go(hasher, cursor, data).stream
      } yield result
  }
}

object DiskStorageSaveFile {
  def initLocation(
      upload: DiskUploadingFile,
      uuid: UUID
  ): IO[(Path, Path)] =
    for {
      (resolved, relative) <- computeLocation(upload.project, upload.volume, upload.filename, uuid)
      dir                  <- IO.fromOption(resolved.parent)(couldNotCreateDirectory(resolved, "No parent path is available"))
      _                    <- Files[IO].createDirectories(dir).adaptError { e => couldNotCreateDirectory(dir, e.getMessage) }
    } yield resolved -> relative

  private def computeLocation(
      project: ProjectRef,
      volume: AbsolutePath,
      filename: String,
      uuid: UUID
  ): IO[(Path, Path)] = IO.delay {
    val intermediate = intermediateFolders(project, uuid, filename)
    val relative     = Path(intermediate)
    val resolved     = Path.fromNioPath(volume.value.resolve(relative.toNioPath))
    (resolved, relative)
  }

  private def couldNotCreateDirectory(directory: Path, message: String) =
    CouldNotCreateIntermediateDirectory(directory.toString, message)

  private def fs2PathToUriPath(path: Path): Uri.Path = {
    val segments = path.names.map { name =>
      Segment.encoded(Uri.pathEncode(name.fileName.toString))
    }
    Uri.Path(segments.toVector)
  }
}
