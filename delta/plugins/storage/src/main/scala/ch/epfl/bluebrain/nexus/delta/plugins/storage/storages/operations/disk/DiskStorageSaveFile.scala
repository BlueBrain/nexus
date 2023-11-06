package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.disk

import akka.actor.ActorSystem
import akka.http.scaladsl.model.{BodyPartEntity, Uri}
import akka.stream.IOOperationIncompleteException
import akka.stream.scaladsl.FileIO
import cats.effect.{ContextShift, IO}
import cats.implicits.catsSyntaxMonadError
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileAttributes.FileAttributesOrigin.Client
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.{FileAttributes, FileDescription}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.Storage.DiskStorage
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageValue.DiskStorageValue
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.SaveFile
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.SaveFile.{digestSink, intermediateFolders}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.StorageFileRejection.SaveFileRejection._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.disk.DiskStorageSaveFile.initLocation
import ch.epfl.bluebrain.nexus.delta.plugins.storage.utils.SinkUtils
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef

import java.nio.file.StandardOpenOption._
import java.nio.file._
import java.util.UUID
import scala.concurrent.Future

final class DiskStorageSaveFile(storage: DiskStorage)(implicit as: ActorSystem, cs: ContextShift[IO]) extends SaveFile {

  import as.dispatcher

  private val openOpts: Set[OpenOption] = Set(CREATE_NEW, WRITE)

  @SuppressWarnings(Array("IsInstanceOf"))
  override def apply(description: FileDescription, entity: BodyPartEntity): IO[FileAttributes] =
    initLocation(storage.project, storage.value, description.uuid, description.filename).flatMap {
      case (fullPath, relativePath) =>
        IO.fromFuture(
          IO.delay(
            entity.dataBytes.runWith(
              SinkUtils.combineMat(digestSink(storage.value.algorithm), FileIO.toPath(fullPath, openOpts)) {
                case (digest, ioResult) if fullPath.toFile.exists() =>
                  Future.successful(
                    FileAttributes(
                      uuid = description.uuid,
                      location = Uri(fullPath.toUri.toString),
                      path = Uri.Path(relativePath.toString),
                      filename = description.filename,
                      mediaType = description.mediaType,
                      bytes = ioResult.count,
                      digest = digest,
                      origin = Client
                    )
                  )
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

}

object DiskStorageSaveFile {
  def initLocation(
      project: ProjectRef,
      disk: DiskStorageValue,
      uuid: UUID,
      filename: String
  ): IO[(Path, Path)] = {
    val relativePath = intermediateFolders(project, uuid, filename)
    for {
      relative <- IO.delay(Paths.get(relativePath)).adaptError(wrongPath(relativePath, _))
      resolved <- IO.delay(disk.volume.value.resolve(relative)).adaptError(wrongPath(relativePath, _))
      dir       = resolved.getParent
      _        <- IO.delay(Files.createDirectories(dir)).adaptError(couldNotCreateDirectory(dir, _))
    } yield resolved -> relative
  }

  private def wrongPath(path: String, err: Throwable) =
    UnexpectedLocationFormat(path, err.getMessage)

  private def couldNotCreateDirectory(directory: Path, err: Throwable) =
    CouldNotCreateIntermediateDirectory(directory.toString, err.getMessage)
}
