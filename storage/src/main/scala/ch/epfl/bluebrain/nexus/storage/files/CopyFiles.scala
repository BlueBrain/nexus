package ch.epfl.bluebrain.nexus.storage.files

import cats.data.NonEmptyList
import cats.effect.{IO, Ref}
import cats.implicits.catsSyntaxParallelTraverse1
import ch.epfl.bluebrain.nexus.storage.StorageError.CopyOperationFailed
import fs2.io.file.{CopyFlag, CopyFlags, Files, Path}

trait CopyFiles {
  def copyValidated(files: NonEmptyList[ValidatedCopyFile]): IO[Unit]
}

object CopyFiles {
  def mk(): CopyFiles = files =>
    copyAll(files.map(v => CopyBetween(Path.fromNioPath(v.absSourcePath), Path.fromNioPath(v.absDestPath))))

  final private[files] case class CopyBetween(source: Path, dest: Path)

  def copyAll(files: NonEmptyList[CopyBetween]): IO[Unit] =
    Ref.of[IO, Option[CopyOperationFailed]](None).flatMap { errorRef =>
      files
        .parTraverse { case CopyBetween(source, dest) =>
          copySingle(source, dest).onError(_ => errorRef.set(Some(CopyOperationFailed(source, dest))))
        }
        .void
        .handleErrorWith(_ => rollbackCopiesAndRethrow(errorRef, files.map(_.dest)))
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
          .filterNot(_ == error.dest)
          .parTraverse(dest => Files[IO].deleteRecursively(parent(dest)).attempt.void) >>
          IO.raiseError(error)
      case None        => IO.unit
    }
}
