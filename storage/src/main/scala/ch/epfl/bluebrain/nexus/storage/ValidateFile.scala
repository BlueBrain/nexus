package ch.epfl.bluebrain.nexus.storage

import akka.http.scaladsl.model.Uri
import akka.stream.Materializer
import akka.stream.alpakka.file.scaladsl.Directory
import akka.stream.scaladsl.Sink
import cats.data.EitherT
import cats.effect.Effect
import cats.implicits._
import ch.epfl.bluebrain.nexus.storage.Rejection.{PathAlreadyExists, PathContainsLinks, PathNotFound}
import ch.epfl.bluebrain.nexus.storage.StorageError.PathInvalid
import ch.epfl.bluebrain.nexus.storage.config.AppConfig.StorageConfig

import java.nio.file.{Files, Path}
import scala.concurrent.ExecutionContext

trait ValidateFile[F[_]] {
  def forCreate(name: String, destPath: Uri.Path): F[ValidatedCreateFile]

  def forMoveIntoProtectedDir(
      name: String,
      sourcePath: Uri.Path,
      destPath: Uri.Path
  ): F[RejOr[ValidatedMoveFile]]

  def forCopyWithinProtectedDir(
      name: String,
      sourcePath: Uri.Path,
      destPath: Uri.Path
  ): F[RejOr[ValidatedCopyFile]]
}

sealed abstract case class ValidatedCreateFile(absDestPath: Path)
sealed abstract case class ValidatedMoveFile(name: String, absSourcePath: Path, absDestPath: Path, isDir: Boolean)
sealed abstract case class ValidatedCopyFile(name: String, absSourcePath: Path, absDestPath: Path)

object ValidateFile {

  def mk[F[_]](config: StorageConfig)(implicit F: Effect[F], ec: ExecutionContext, mt: Materializer): ValidateFile[F] =
    new ValidateFile[F] {

      override def forCreate(name: String, destPath: Uri.Path): F[ValidatedCreateFile] = {
        val absDestPath = filePath(config, name, destPath)
        throwIf(!descendantOf(absDestPath, basePath(config, name)), PathInvalid(name, destPath))
          .as(new ValidatedCreateFile(absDestPath) {})
      }

      override def forMoveIntoProtectedDir(
          name: String,
          sourcePath: Uri.Path,
          destPath: Uri.Path
      ): F[RejOr[ValidatedMoveFile]] = {

        val bucketPath          = basePath(config, name, protectedDir = false)
        val bucketProtectedPath = basePath(config, name)
        val absSourcePath       = filePath(config, name, sourcePath, protectedDir = false)
        val absDestPath         = filePath(config, name, destPath)

        def checkIfSourceIsFileOrDir: EitherT[F, Rejection, Boolean] =
          EitherT
            .right[Rejection](isRegularFile(absSourcePath))
            .ifM(
              ifTrue = EitherT.pure(false),
              ifFalse = checkIfSourceIsValidDirectory
            )

        def checkIfSourceIsValidDirectory: EitherT[F, Rejection, Boolean] =
          EitherT
            .right[Rejection](isDirectory(absSourcePath))
            .ifM(
              ifTrue = EitherT(rejectIfDirContainsLink(name, sourcePath, absSourcePath)).as(true),
              ifFalse = EitherT.leftT(PathNotFound(name, sourcePath))
            )

        def notFound = PathNotFound(name, sourcePath)

        (for {
          _     <- EitherT(rejectIf(fileExists(absSourcePath).map(!_), notFound))
          _     <- EitherT(rejectIf(descendantOf(absSourcePath, bucketProtectedPath).pure[F], notFound))
          _     <- EitherT.right(throwIf(!allowedPrefix(config, bucketPath, absSourcePath), PathInvalid(name, sourcePath)))
          _     <- EitherT.right(throwIf(!descendantOf(absDestPath, bucketProtectedPath), PathInvalid(name, destPath)))
          _     <- EitherT(rejectIf(fileExists(absDestPath), PathAlreadyExists(name, destPath)))
          _     <- EitherT(rejectIfFileIsSymbolicLinkOrContainsHardLink(name, sourcePath, absSourcePath))
          isDir <- checkIfSourceIsFileOrDir
        } yield new ValidatedMoveFile(name, absSourcePath, absDestPath, isDir) {}).value
      }

      override def forCopyWithinProtectedDir(
          name: String,
          sourcePath: Uri.Path,
          destPath: Uri.Path
      ): F[RejOr[ValidatedCopyFile]] = {

        val bucketProtectedPath = basePath(config, name)
        val absSourcePath       = filePath(config, name, sourcePath, protectedDir = false)
        val absDestPath         = filePath(config, name, destPath)

        def notFound = PathNotFound(name, sourcePath)

        (for {
          _      <- EitherT(rejectIf(fileExists(absSourcePath).map(!_), notFound))
          _      <- EitherT(rejectIf((!descendantOf(absSourcePath, bucketProtectedPath)).pure[F], notFound))
          _      <- EitherT.right(throwIf(!descendantOf(absDestPath, bucketProtectedPath), PathInvalid(name, destPath)))
          _      <- EitherT(rejectIf(fileExists(absDestPath), PathAlreadyExists(name, destPath)))
          isFile <- EitherT.right[Rejection](isRegularFile(absSourcePath))
          _      <- EitherT.right[Rejection](throwIf(!isFile, PathInvalid(name, sourcePath)))
        } yield new ValidatedCopyFile(name, absSourcePath, absDestPath) {}).value
      }

      private def fileExists(absSourcePath: Path): F[Boolean]     = F.delay(Files.exists(absSourcePath))
      private def isRegularFile(absSourcePath: Path): F[Boolean]  = F.delay(Files.isRegularFile(absSourcePath))
      private def isDirectory(absSourcePath: Path): F[Boolean]    = F.delay(Files.isDirectory(absSourcePath))
      private def isSymbolicLink(absSourcePath: Path): F[Boolean] = F.delay(Files.isSymbolicLink(absSourcePath))

      private def allowedPrefix(config: StorageConfig, bucketPath: Path, absSourcePath: Path) =
        absSourcePath.startsWith(bucketPath) ||
          config.extraPrefixes.exists(absSourcePath.startsWith)

      private def containsHardLink(absPath: Path): F[Boolean] =
        F.delay(Files.isDirectory(absPath)).flatMap {
          case true  => false.pure[F]
          case false =>
            F.delay(Files.getAttribute(absPath, "unix:nlink").asInstanceOf[Int]).map(_ > 1)
        }

      def rejectIfFileIsSymbolicLinkOrContainsHardLink(
          name: String,
          sourcePath: Uri.Path,
          absSourcePath: Path
      ): F[RejOr[Unit]] =
        rejectIf(
          (isSymbolicLink(absSourcePath), containsHardLink(absSourcePath)).mapN(_ || _),
          PathContainsLinks(name, sourcePath)
        )

      def dirContainsLink(path: Path): F[Boolean] =
        Directory
          .walk(path)
          .mapAsync(1)(p => F.toIO((isSymbolicLink(p), containsHardLink(p)).mapN(_ || _)).unsafeToFuture())
          .takeWhile(_ == false, inclusive = true)
          .runWith(Sink.last)
          .to[F]

      def rejectIfDirContainsLink(name: String, sourcePath: Uri.Path, path: Path): F[RejOr[Unit]] =
        rejectIf(dirContainsLink(path), PathContainsLinks(name, sourcePath))

      private def rejectIf(cond: F[Boolean], rej: Rejection): F[RejOr[Unit]] = cond.ifF(Left(rej), Right(()))

      private def throwIf(cond: Boolean, e: StorageError): F[Unit] = F.raiseWhen(cond)(e)
    }
}
