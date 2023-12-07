package ch.epfl.bluebrain.nexus.delta.kernel.utils

import cats.data.NonEmptyList
import cats.effect.{IO, Ref}
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import ch.epfl.bluebrain.nexus.delta.kernel.error.Rejection
import fs2.io.file.{CopyFlag, CopyFlags, Files, Path}

trait TransactionalFileCopier {
  def copyAll(files: NonEmptyList[CopyBetween]): IO[Unit]
}

final case class CopyBetween(source: Path, destination: Path)

final case class CopyOperationFailed(failingCopy: CopyBetween, e: Throwable) extends Rejection {
  override def reason: String =
    s"Copy operation failed from source ${failingCopy.source} to destination ${failingCopy.destination}. Underlying error: $e"
}

object TransactionalFileCopier {

  private val logger = Logger[TransactionalFileCopier]

  def mk(): TransactionalFileCopier = files => copyAll(files)

  private def copyAll(files: NonEmptyList[CopyBetween]): IO[Unit] =
    Ref.of[IO, Option[CopyOperationFailed]](None).flatMap { errorRef =>
      files
        .parTraverse { case c @ CopyBetween(source, dest) =>
          copySingle(source, dest).onError(e => errorRef.set(Some(CopyOperationFailed(c, e))))
        }
        .void
        .handleErrorWith { e =>
          val destinations = files.map(_.destination)
          logger.error(e)(s"Transactional files copy failed, deleting created files: ${destinations}") >>
            rollbackCopiesAndRethrow(errorRef, destinations)
        }
    }

  def parent(p: Path): Path = Path.fromNioPath(p.toNioPath.getParent)

  private def copySingle(source: Path, dest: Path): IO[Unit] =
    for {
      _           <- Files[IO].createDirectories(parent(dest))
      _           <- Files[IO].copy(source, dest, CopyFlags(CopyFlag.CopyAttributes))
      // the copy attributes flag won't always preserve permissions due to umask
      sourcePerms <- Files[IO].getPosixPermissions(source)
      _           <- Files[IO].setPosixPermissions(dest, sourcePerms)
    } yield ()

  private def rollbackCopiesAndRethrow(
      errorRef: Ref[IO, Option[CopyOperationFailed]],
      files: NonEmptyList[Path]
  ): IO[Unit] =
    errorRef.get.flatMap {
      case Some(error) =>
        files
          .filterNot(_ == error.failingCopy.destination)
          .parTraverse(dest => Files[IO].deleteRecursively(parent(dest)).attempt.void) >>
          IO.raiseError(error)
      case None        => IO.unit
    }
}
