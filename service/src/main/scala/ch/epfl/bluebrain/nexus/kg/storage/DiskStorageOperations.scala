package ch.epfl.bluebrain.nexus.kg.storage

import java.nio.file.{Files, Path, Paths}
import java.util.UUID

import akka.actor.ActorSystem
import akka.http.scaladsl.model.Uri
import akka.stream.scaladsl.{FileIO, Keep}
import cats.effect.{ContextShift, Effect, IO}
import cats.implicits._
import ch.epfl.bluebrain.nexus.kg.KgError
import ch.epfl.bluebrain.nexus.kg.resources.ResId
import ch.epfl.bluebrain.nexus.kg.resources.file.File._
import ch.epfl.bluebrain.nexus.kg.storage.Storage._
import ch.epfl.bluebrain.nexus.storage.client.types.{FileAttributes => StorageFileAttributes}
import com.typesafe.scalalogging.Logger

import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

object DiskStorageOperations {

  private val logger = Logger[this.type]

  /**
    * [[VerifyStorage]] implementation for [[DiskStorage]]
    *
    * @param storage the [[DiskStorage]]
    */
  final class VerifyDiskStorage[F[_]](storage: DiskStorage)(implicit F: Effect[F]) extends VerifyStorage[F] {
    override def apply: F[Either[String, Unit]] =
      if (!Files.exists(storage.volume)) F.pure(Left(s"Volume '${storage.volume}' does not exist."))
      else if (!Files.isDirectory(storage.volume)) F.pure(Left(s"Volume '${storage.volume}' is not a directory."))
      else if (!Files.isWritable(storage.volume))
        F.pure(Left(s"Volume '${storage.volume}' does not have write access."))
      else F.pure(Right(()))
  }

  /**
    * [[FetchFile]] implementation for [[DiskStorage]]
    *
    */
  final class FetchDiskFile[F[_]](implicit F: Effect[F]) extends FetchFile[F, AkkaSource] {

    override def apply(fileMeta: FileAttributes): F[AkkaSource] =
      uriToPath(fileMeta.location) match {
        case Some(path) => F.pure(FileIO.fromPath(path))
        case None       =>
          logger.error(s"Invalid file location: '${fileMeta.location}'")
          F.raiseError(KgError.InternalError(s"Invalid file location: '${fileMeta.location}'"))
      }
  }

  /**
    * [[SaveFile]] implementation for [[DiskStorage]]
    *
    * @param storage the [[DiskStorage]]
    */
  final class SaveDiskFile[F[_]](storage: DiskStorage)(implicit F: Effect[F], as: ActorSystem)
      extends SaveFile[F, AkkaSource] {

    implicit private val ec: ExecutionContext           = as.dispatcher
    implicit private val contextShift: ContextShift[IO] = IO.contextShift(ec)

    override def apply(id: ResId, fileDesc: FileDescription, source: AkkaSource): F[FileAttributes] =
      getLocation(fileDesc.uuid, fileDesc.filename).flatMap {
        case (fullPath, relativePath) =>
          val future = source
            .alsoToMat(digestSink(storage.algorithm))(Keep.right)
            .toMat(FileIO.toPath(fullPath)) {
              case (digFuture, ioFuture) =>
                digFuture.zipWith(ioFuture) {
                  case (dig, io) if fullPath.toFile.exists() =>
                    val summary = StoredSummary(pathToUri(fullPath), relativePath, io.count, dig)
                    Future.successful(fileDesc.process(summary))
                  case _                                     =>
                    Future.failed(
                      KgError.InternalError(
                        s"I/O error writing file with contentType '${fileDesc.defaultMediaType}' and filename '${fileDesc.filename}'"
                      )
                    )
                }
            }
            .run()
            .flatten

          F.liftIO(IO.fromFuture(IO(future)))
      }

    private def getLocation(uuid: UUID, filename: String): F[(Path, Uri.Path)] =
      F.catchNonFatal {
          val relative = Paths.get(mangle(storage.ref, uuid, filename))
          val filePath = storage.volume.resolve(relative)
          Files.createDirectories(filePath.getParent)
          (filePath, Uri.Path(relative.toString))
        }
        .recoverWith {
          case NonFatal(err) =>
            logger.error(s"Unable to resolve location for path '${storage.volume}'", err)
            F.raiseError(KgError.InternalError(s"Unable to resolve location for path '${storage.volume}'"))
        }
  }

  /**
    * [[LinkFile]] implementation for [[DiskStorage]] that always throws an error since this operation is not supported.
    */
  final class LinkDiskFile[F[_]]()(implicit F: Effect[F]) extends LinkFile[F] {
    override def apply(id: ResId, fileDesc: FileDescription, path: Uri.Path): F[FileAttributes] =
      F.raiseError(KgError.UnsupportedOperation)
  }

  /**
    * [[FetchFileAttributes]] implementation for [[DiskStorage]] that always throws an error since this operation is not supported.
    * This is the case because linkFile is also not supported. Use a ''RemoteDiskStorage'' if you want to have this functionality.
    */
  final class FetchAttributes[F[_]]()(implicit F: Effect[F]) extends FetchFileAttributes[F] {
    override def apply(path: Uri.Path): F[StorageFileAttributes] =
      F.raiseError(KgError.UnsupportedOperation)
  }
}
