package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.disk

import akka.actor.ActorSystem
import akka.http.scaladsl.model.Uri
import akka.stream.IOOperationIncompleteException
import akka.stream.scaladsl.FileIO
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileAttributes.FileAttributesOrigin.Client
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.{FileAttributes, FileDescription}
import ch.epfl.bluebrain.nexus.delta.sdk.AkkaSource
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.Storage.DiskStorage
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.SaveFile
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.SaveFile.{digestSink, intermediateFolders}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.StorageFileRejection.SaveFileRejection
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.StorageFileRejection.SaveFileRejection._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.utils.SinkUtils
import monix.bio.IO

import java.nio.file.StandardOpenOption._
import java.nio.file.{FileAlreadyExistsException, Files, OpenOption, Path, Paths}
import java.util.UUID
import scala.concurrent.Future
import scala.util.Try

final class DiskStorageSaveFile(storage: DiskStorage)(implicit as: ActorSystem) extends SaveFile {

  import as.dispatcher

  private val openOpts: Set[OpenOption] = Set(CREATE_NEW, WRITE)

  override def apply(description: FileDescription, source: AkkaSource): IO[SaveFileRejection, FileAttributes] =
    initLocation(description.uuid, description.filename).flatMap { case (fullPath, relativePath) =>
      IO.deferFuture(
        source.runWith(SinkUtils.combineMat(digestSink(storage.value.algorithm), FileIO.toPath(fullPath, openOpts)) {
          case (digest, ioResult) if fullPath.toFile.exists() =>
            Future.successful(
              FileAttributes(
                uuid = description.uuid,
                location = Uri(fullPath.toUri.toString),
                path = relativePath,
                filename = description.filename,
                mediaType = description.mediaType,
                bytes = ioResult.count,
                digest = digest,
                origin = Client
              )
            )
          case _                                              =>
            Future.failed(new IllegalArgumentException("File was not written"))
        })
      ).mapError {
        case _: FileAlreadyExistsException                                                            => ResourceAlreadyExists(fullPath.toString)
        case i: IOOperationIncompleteException if i.getCause.isInstanceOf[FileAlreadyExistsException] =>
          ResourceAlreadyExists(fullPath.toString)
        case err                                                                                      => UnexpectedSaveError(fullPath.toString, err.getMessage)
      }
    }

  private def initLocation(uuid: UUID, filename: String): IO[SaveFileRejection, (Path, Uri.Path)] = {
    val relativeUriPath = intermediateFolders(storage.project, uuid, filename)
    for {
      relative <- ioDelayTry(Paths.get(relativeUriPath.toString), wrongPath(relativeUriPath, _))
      resolved <- ioDelayTry(storage.value.volume.value.resolve(relative), wrongPath(relativeUriPath, _))
      dir       = resolved.getParent
      _        <- ioDelayTry(Files.createDirectories(dir), couldNotCreateDirectory(dir, _))
    } yield resolved -> relativeUriPath
  }

  private def ioDelayTry[A, E <: SaveFileRejection](a: => A, ef: Throwable => E): IO[E, A] =
    IO.deferTotal(IO.fromEither(Try(a).toEither)).mapError(ef)

  private def wrongPath(relativeUriPath: Uri.Path, err: Throwable) =
    UnexpectedLocationFormat(relativeUriPath.toString, err.getMessage)

  private def couldNotCreateDirectory(directory: Path, err: Throwable) =
    CouldNotCreateIntermediateDirectory(directory.toString, err.getMessage)

}
