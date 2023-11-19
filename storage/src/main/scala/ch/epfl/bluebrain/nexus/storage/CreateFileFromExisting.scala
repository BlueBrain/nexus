package ch.epfl.bluebrain.nexus.storage

import akka.http.scaladsl.model.Uri
import akka.stream.Materializer
import akka.stream.alpakka.file.scaladsl.Directory
import akka.stream.scaladsl.Sink
import cats.data.EitherT
import cats.effect.{Effect, Sync}
import cats.implicits._
import ch.epfl.bluebrain.nexus.storage.Rejection.{PathAlreadyExists, PathContainsLinks, PathNotFound}
import ch.epfl.bluebrain.nexus.storage.StorageError.PathInvalid
import ch.epfl.bluebrain.nexus.storage.config.AppConfig.StorageConfig

import java.nio.file.{Files, Path}
import scala.concurrent.ExecutionContext

sealed abstract case class CreateFileFromExisting(name: String, absSourcePath: Path, absDestPath: Path, isDir: Boolean)

object CreateFileFromExisting {
  def forMoveIntoProtectedDir[F[_]](
      config: StorageConfig,
      name: String,
      sourcePath: Uri.Path,
      destPath: Uri.Path
  )(implicit F: Effect[F], ec: ExecutionContext, mt: Materializer): F[RejOr[CreateFileFromExisting]] = {
    val bucketPath          = basePath(config, name, protectedDir = false)
    val bucketProtectedPath = basePath(config, name)
    val absSourcePath       = filePath(config, name, sourcePath, protectedDir = false)
    val absDestPath         = filePath(config, name, destPath)

    (for {
      _     <- EitherT(rejectIf(fileExists(absSourcePath).map(!_), PathNotFound(name, sourcePath)))
      _     <- EitherT(rejectIf(descendantOf(absSourcePath, bucketProtectedPath).pure[F], PathNotFound(name, sourcePath)))
      _     <- EitherT.right(throwIf(!allowedPrefix(config, bucketPath, absSourcePath), PathInvalid(name, sourcePath)))
      _     <- EitherT.right(throwIf(!descendantOf(absDestPath, bucketProtectedPath), PathInvalid(name, destPath)))
      _     <- EitherT(rejectIf(fileExists(absDestPath), PathAlreadyExists(name, destPath)))
      _     <- EitherT(rejectIfFileIsSymbolicLinkOrContainsHardLink(name, sourcePath, absSourcePath))
      isDir <- checkIfFileOrDir(name, sourcePath, absSourcePath)
    } yield new CreateFileFromExisting(name, absSourcePath, absDestPath, isDir) {}).value
  }

  def forCopyFromProtectedDir[F[_]](
      config: StorageConfig,
      name: String,
      sourcePath: Uri.Path,
      destPath: Uri.Path
  )(implicit F: Sync[F]): F[RejOr[CreateFileFromExisting]] = {
    val bucketProtectedPath = basePath(config, name)
    val absSourcePath       = filePath(config, name, sourcePath, protectedDir = false)
    val absDestPath         = filePath(config, name, destPath)

    (for {
      _      <- EitherT(rejectIf(fileExists(absSourcePath).map(!_), PathNotFound(name, sourcePath)))
      _      <-
        EitherT(rejectIf((!descendantOf(absSourcePath, bucketProtectedPath)).pure[F], PathNotFound(name, sourcePath)))
      _      <- EitherT.right(throwIf(!descendantOf(absDestPath, bucketProtectedPath), PathInvalid(name, destPath)))
      _      <- EitherT(rejectIf(fileExists(absDestPath), PathAlreadyExists(name, destPath)))
      isFile <- EitherT.right[Rejection](isRegularFile(absSourcePath))
      _      <- EitherT.right[Rejection](throwIf(!isFile, PathInvalid(name, sourcePath)))
    } yield new CreateFileFromExisting(name, absSourcePath, absDestPath, isDir = false) {}).value
  }

  private def checkIfFileOrDir[F[_]](name: String, sourcePath: Uri.Path, absSourcePath: Path)(implicit
      F: Effect[F],
      ec: ExecutionContext,
      mt: Materializer
  ): EitherT[F, Rejection, Boolean] =
    EitherT
      .right[Rejection](isRegularFile(absSourcePath))
      .ifM(
        ifTrue = EitherT.pure(false),
        ifFalse = checkIfValidDirectory(name, sourcePath, absSourcePath)
      )

  private def checkIfValidDirectory[F[_]](name: String, sourcePath: Uri.Path, absSourcePath: Path)(implicit
      F: Effect[F],
      ec: ExecutionContext,
      mt: Materializer
  ): EitherT[F, Rejection, Boolean] =
    EitherT
      .right[Rejection](isDirectory(absSourcePath))
      .ifM(
        ifTrue = EitherT(rejectIfDirContainsLink(name, sourcePath, absSourcePath)).as(true),
        ifFalse = EitherT.leftT(PathNotFound(name, sourcePath))
      )

  def rejectIfFileIsSymbolicLinkOrContainsHardLink[F[_]](name: String, sourcePath: Uri.Path, absSourcePath: Path)(
      implicit F: Sync[F]
  ): F[RejOr[Unit]] =
    rejectIf(
      (fileIsSymbolicLink(absSourcePath), containsHardLink(absSourcePath)).mapN(_ || _),
      PathContainsLinks(name, sourcePath)
    )

  def rejectIfDirContainsLink[F[_]](name: String, sourcePath: Uri.Path, path: Path)(implicit
      F: Effect[F],
      ec: ExecutionContext,
      mt: Materializer
  ): F[RejOr[Unit]] =
    rejectIf(dirContainsLink(path), PathContainsLinks(name, sourcePath))

  def fileIsSymbolicLink[F[_]](absSourcePath: Path)(implicit F: Sync[F]): F[Boolean] =
    F.delay(Files.isSymbolicLink(absSourcePath))

  def allowedPrefix(config: StorageConfig, bucketPath: Path, absSourcePath: Path) =
    absSourcePath.startsWith(bucketPath) ||
      config.extraPrefixes.exists(absSourcePath.startsWith)

  def fileExists[F[_]](absSourcePath: Path)(implicit F: Sync[F]): F[Boolean] =
    F.delay(Files.exists(absSourcePath))

  def isRegularFile[F[_]](absSourcePath: Path)(implicit F: Sync[F]): F[Boolean] =
    F.delay(Files.isRegularFile(absSourcePath))

  def isDirectory[F[_]](absSourcePath: Path)(implicit F: Sync[F]): F[Boolean] =
    F.delay(Files.isDirectory(absSourcePath))

  private def rejectIf[F[_]](cond: F[Boolean], rej: Rejection)(implicit F: Sync[F]): F[RejOr[Unit]] =
    cond.ifF(Left(rej), Right(()))

  private def throwIf[F[_]](cond: Boolean, e: StorageError)(implicit F: Sync[F]): F[Unit] =
    if (cond) F.raiseError(e) else F.unit

  private def containsHardLink[F[_]](absPath: Path)(implicit F: Sync[F]): F[Boolean] =
    F.delay(Files.isDirectory(absPath)).flatMap {
      case true  => false.pure[F]
      case false =>
        F.delay(Files.getAttribute(absPath, "unix:nlink").asInstanceOf[Int]).map(_ > 1)
    }

  def dirContainsLink[F[_]](path: Path)(implicit
      F: Effect[F],
      ec: ExecutionContext,
      mt: Materializer
  ): F[Boolean] =
    Directory
      .walk(path)
      .mapAsync(1)(p => F.toIO((fileIsSymbolicLink(p), containsHardLink(p)).mapN(_ || _)).unsafeToFuture())
      .takeWhile(_ == false, inclusive = true)
      .runWith(Sink.last)
      .to[F]
}
