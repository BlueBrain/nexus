package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.disk

import akka.actor.ActorSystem
import akka.http.scaladsl.model.{BodyPartEntity, Uri}
import akka.stream.IOOperationIncompleteException
import akka.stream.scaladsl.{FileIO, Sink}
import akka.util.ByteString
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.Hex
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.Digest.ComputedDigest
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileAttributes.FileAttributesOrigin.Client
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.{Digest, FileStorageMetadata}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.{AbsolutePath, DigestAlgorithm}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.FileOperations.intermediateFolders
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.StorageFileRejection.SaveFileRejection._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.UploadingFile.DiskUploadingFile
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.disk.DiskStorageSaveFile.initLocation
import ch.epfl.bluebrain.nexus.delta.plugins.storage.utils.SinkUtils
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef

import java.nio.file.StandardOpenOption._
import java.nio.file._
import java.util.UUID
import scala.concurrent.Future

final class DiskStorageSaveFile(implicit as: ActorSystem, uuidf: UUIDF) {

  import as.dispatcher

  private val openOpts: Set[OpenOption] = Set(CREATE_NEW, WRITE)

  def apply(uploading: DiskUploadingFile): IO[FileStorageMetadata] = {
    for {
      uuid                     <- uuidf()
      (fullPath, relativePath) <- initLocation(uploading, uuid)
      (size, digest)           <- storeFile(uploading.entity, uploading.algorithm, fullPath)
    } yield FileStorageMetadata(
      uuid = uuid,
      bytes = size,
      digest = digest,
      origin = Client,
      location = Uri(fullPath.toUri.toString),
      path = Uri.Path(relativePath.toString)
    )
  }

  @SuppressWarnings(Array("IsInstanceOf"))
  private def storeFile(entity: BodyPartEntity, algorithm: DigestAlgorithm, fullPath: Path): IO[(Long, Digest)] = {
    IO.fromFuture(
      IO.delay(
        entity.dataBytes.runWith(
          SinkUtils.combineMat(digestSink(algorithm), FileIO.toPath(fullPath, openOpts)) {
            case (digest, ioResult) if fullPath.toFile.exists() =>
              Future.successful(ioResult.count -> digest)
            case _                                              =>
              Future.failed(new IllegalArgumentException("File was not written"))
          }
        )
      )
    ).adaptError {
      case _: FileAlreadyExistsException                                                            => ResourceAlreadyExists(fullPath.toString)
      case i: IOOperationIncompleteException if i.getCause.isInstanceOf[FileAlreadyExistsException] =>
        ResourceAlreadyExists(fullPath.toString)
      case err                                                                                      => UnexpectedSaveError(fullPath.toString, err.getMessage)
    }
  }

  /**
    * A sink that computes the digest of the input ByteString
    *
    * @param algorithm
    *   the digest algorithm. E.g.: SHA-256
    */
  private def digestSink(algorithm: DigestAlgorithm): Sink[ByteString, Future[ComputedDigest]] =
    Sink
      .fold(algorithm.digest) { (digest, currentBytes: ByteString) =>
        digest.update(currentBytes.asByteBuffer)
        digest
      }
      .mapMaterializedValue(_.map(dig => ComputedDigest(algorithm, Hex.valueOf(dig.digest))))
}

object DiskStorageSaveFile {
  def initLocation(
      upload: DiskUploadingFile,
      uuid: UUID
  ): IO[(Path, Path)] =
    for {
      (resolved, relative) <- computeLocation(upload.project, upload.volume, upload.filename, uuid)
      dir                   = resolved.getParent
      _                    <- IO.blocking(Files.createDirectories(dir)).adaptError(couldNotCreateDirectory(dir, _))
    } yield resolved -> relative

  def computeLocation(
      project: ProjectRef,
      volume: AbsolutePath,
      filename: String,
      uuid: UUID
  ): IO[(Path, Path)] = {
    val relativePath = intermediateFolders(project, uuid, filename)
    for {
      relative <- IO.delay(Paths.get(relativePath)).adaptError(wrongPath(relativePath, _))
      resolved <- IO.delay(volume.value.resolve(relative)).adaptError(wrongPath(relativePath, _))
    } yield resolved -> relative
  }

  private def wrongPath(path: String, err: Throwable) =
    UnexpectedLocationFormat(path, err.getMessage)

  private def couldNotCreateDirectory(directory: Path, err: Throwable) =
    CouldNotCreateIntermediateDirectory(directory.toString, err.getMessage)
}
