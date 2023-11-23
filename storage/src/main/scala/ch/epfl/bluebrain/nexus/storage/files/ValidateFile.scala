package ch.epfl.bluebrain.nexus.storage.files

import akka.http.scaladsl.model.Uri
import akka.stream.Materializer
import akka.stream.alpakka.file.scaladsl.Directory
import akka.stream.scaladsl.Sink
import cats.data.EitherT
import cats.effect.IO
import cats.effect.unsafe.implicits._
import cats.implicits._
import ch.epfl.bluebrain.nexus.storage.Rejection.{PathAlreadyExists, PathContainsLinks, PathNotFound}
import ch.epfl.bluebrain.nexus.storage.StorageError.PathInvalid
import ch.epfl.bluebrain.nexus.storage.config.AppConfig.StorageConfig
import ch.epfl.bluebrain.nexus.storage.{basePath, descendantOf, filePath, RejOr, Rejection, StorageError}

import java.nio.file.{Files, Path}

trait ValidateFile {
  def forCreate(name: String, destPath: Uri.Path): IO[ValidatedCreateFile]

  def forMoveIntoProtectedDir(
      name: String,
      sourcePath: Uri.Path,
      destPath: Uri.Path
  ): IO[RejOr[ValidatedMoveFile]]

  def forCopyWithinProtectedDir(
      name: String,
      sourcePath: Uri.Path,
      destPath: Uri.Path
  ): IO[RejOr[ValidatedCopyFile]]
}

sealed abstract case class ValidatedCreateFile(absDestPath: Path)
sealed abstract case class ValidatedMoveFile(name: String, absSourcePath: Path, absDestPath: Path, isDir: Boolean)
sealed abstract case class ValidatedCopyFile(name: String, absSourcePath: Path, absDestPath: Path)

object ValidateFile {

  def mk(config: StorageConfig)(implicit mt: Materializer): ValidateFile =
    new ValidateFile {

      override def forCreate(name: String, destPath: Uri.Path): IO[ValidatedCreateFile] = {
        val absDestPath = filePath(config, name, destPath)
        throwIf(!descendantOf(absDestPath, basePath(config, name)), PathInvalid(name, destPath))
          .as(new ValidatedCreateFile(absDestPath) {})
      }

      override def forMoveIntoProtectedDir(
          name: String,
          sourcePath: Uri.Path,
          destPath: Uri.Path
      ): IO[RejOr[ValidatedMoveFile]] = {

        val bucketPath          = basePath(config, name, protectedDir = false)
        val bucketProtectedPath = basePath(config, name)
        val absSourcePath       = filePath(config, name, sourcePath, protectedDir = false)
        val absDestPath         = filePath(config, name, destPath)

        def notFound = PathNotFound(name, sourcePath)

        (for {
          _     <- EitherT(rejectIf(fileExists(absSourcePath).map(!_), notFound))
          _     <- EitherT(rejectIf(descendantOf(absSourcePath, bucketProtectedPath).pure[IO], notFound))
          _     <- EitherT.right(throwIf(!allowedPrefix(config, bucketPath, absSourcePath), PathInvalid(name, sourcePath)))
          _     <- EitherT.right(throwIf(!descendantOf(absDestPath, bucketProtectedPath), PathInvalid(name, destPath)))
          _     <- EitherT(rejectIf(fileExists(absDestPath), PathAlreadyExists(name, destPath)))
          _     <- EitherT(rejectIfFileIsSymbolicLinkOrContainsHardLink(name, sourcePath, absSourcePath))
          isDir <- checkIfSourceIsFileOrDir(name, sourcePath, absSourcePath)
        } yield new ValidatedMoveFile(name, absSourcePath, absDestPath, isDir) {}).value
      }

      override def forCopyWithinProtectedDir(
          name: String,
          sourcePath: Uri.Path,
          destPath: Uri.Path
      ): IO[RejOr[ValidatedCopyFile]] = {

        val bucketProtectedPath = basePath(config, name)
        val absSourcePath       = filePath(config, name, sourcePath)
        val absDestPath         = filePath(config, name, destPath)

        def notFound = PathNotFound(name, sourcePath)

        (for {
          _      <- EitherT(rejectIf(fileExists(absSourcePath).map(!_), notFound))
          _      <- EitherT(rejectIf((!descendantOf(absSourcePath, bucketProtectedPath)).pure[IO], notFound))
          _      <- EitherT.right(throwIf(!descendantOf(absDestPath, bucketProtectedPath), PathInvalid(name, destPath)))
          _      <- EitherT(rejectIf(fileExists(absDestPath), PathAlreadyExists(name, destPath)))
          isFile <- EitherT.right[Rejection](isRegularFile(absSourcePath))
          _      <- EitherT.right[Rejection](throwIf(!isFile, PathInvalid(name, sourcePath)))
        } yield new ValidatedCopyFile(name, absSourcePath, absDestPath) {}).value
      }

      def fileExists(absSourcePath: Path): IO[Boolean]     = IO.blocking(Files.exists(absSourcePath))
      def isRegularFile(absSourcePath: Path): IO[Boolean]  = IO.blocking(Files.isRegularFile(absSourcePath))
      def isDirectory(absSourcePath: Path): IO[Boolean]    = IO.blocking(Files.isDirectory(absSourcePath))
      def isSymbolicLink(absSourcePath: Path): IO[Boolean] = IO.blocking(Files.isSymbolicLink(absSourcePath))

      def allowedPrefix(config: StorageConfig, bucketPath: Path, absSourcePath: Path) =
        absSourcePath.startsWith(bucketPath) ||
          config.extraPrefixes.exists(absSourcePath.startsWith)

      def containsHardLink(absPath: Path): IO[Boolean] =
        IO.blocking(Files.isDirectory(absPath)).flatMap {
          case true  => false.pure[IO]
          case false =>
            IO.blocking(Files.getAttribute(absPath, "unix:nlink").asInstanceOf[Int]).map(_ > 1)
        }

      def checkIfSourceIsFileOrDir(
          name: String,
          sourcePath: Uri.Path,
          absSourcePath: Path
      ): EitherT[IO, Rejection, Boolean] =
        EitherT
          .right[Rejection](isRegularFile(absSourcePath))
          .ifM(
            ifTrue = EitherT.pure(false),
            ifFalse = checkIfSourceIsValidDirectory(name, sourcePath, absSourcePath)
          )

      def checkIfSourceIsValidDirectory(
          name: String,
          sourcePath: Uri.Path,
          absSourcePath: Path
      ): EitherT[IO, Rejection, Boolean] =
        EitherT
          .right[Rejection](isDirectory(absSourcePath))
          .ifM(
            ifTrue = EitherT(rejectIfDirContainsLink(name, sourcePath, absSourcePath)).as(true),
            ifFalse = EitherT.leftT(PathNotFound(name, sourcePath))
          )

      def rejectIfFileIsSymbolicLinkOrContainsHardLink(
          name: String,
          sourcePath: Uri.Path,
          absSourcePath: Path
      ): IO[RejOr[Unit]] =
        rejectIf(
          (isSymbolicLink(absSourcePath), containsHardLink(absSourcePath)).mapN(_ || _),
          PathContainsLinks(name, sourcePath)
        )

      def dirContainsLink(path: Path): IO[Boolean] =
        IO.fromFuture {
          IO.delay {
            Directory
              .walk(path)
              .mapAsync(1)(p => (isSymbolicLink(p), containsHardLink(p)).mapN(_ || _).unsafeToFuture())
              .takeWhile(_ == false, inclusive = true)
              .runWith(Sink.last)
          }
        }

      def rejectIfDirContainsLink(name: String, sourcePath: Uri.Path, path: Path): IO[RejOr[Unit]] =
        rejectIf(dirContainsLink(path), PathContainsLinks(name, sourcePath))

      def rejectIf(cond: IO[Boolean], rej: Rejection): IO[RejOr[Unit]] = cond.ifF(Left(rej), Right(()))

      def throwIf(cond: Boolean, e: StorageError): IO[Unit] = IO.raiseWhen(cond)(e)
    }
}
