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
      sourceBucket: String,
      destBucket: String,
      sourcePath: Uri.Path,
      destPath: Uri.Path
  ): IO[RejOr[ValidatedCopyFile]]
}

sealed abstract case class ValidatedCreateFile(absDestPath: Path)
sealed abstract case class ValidatedMoveFile(name: String, absSourcePath: Path, absDestPath: Path, isDir: Boolean)
sealed abstract case class ValidatedCopyFile(
    sourceBucket: String,
    destBucket: String,
    absSourcePath: Path,
    absDestPath: Path
)

object ValidateFile {

  def mk(config: StorageConfig)(implicit mt: Materializer): ValidateFile =
    new ValidateFile {

      override def forCreate(name: String, destPath: Uri.Path): IO[ValidatedCreateFile] = {
        val absDestPath = filePath(config, name, destPath)
        throwIfIO(!descendantOf(absDestPath, basePath(config, name)), PathInvalid(name, destPath))
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
          _     <- rejectIf(fileExists(absSourcePath).map(!_), notFound)
          _     <- rejectIf(descendantOf(absSourcePath, bucketProtectedPath).pure[IO], notFound)
          _     <- throwIf(!allowedPrefix(config, bucketPath, absSourcePath), PathInvalid(name, sourcePath))
          _     <- throwIf(!descendantOf(absDestPath, bucketProtectedPath), PathInvalid(name, destPath))
          _     <- rejectIf(fileExists(absDestPath), PathAlreadyExists(name, destPath))
          _     <- rejectIfFileIsSymbolicLinkOrContainsHardLink(name, sourcePath, absSourcePath)
          isDir <- checkIfSourceIsFileOrDir(name, sourcePath, absSourcePath)
        } yield new ValidatedMoveFile(name, absSourcePath, absDestPath, isDir) {}).value
      }

      override def forCopyWithinProtectedDir(
          sourceBucket: String,
          destBucket: String,
          sourcePath: Uri.Path,
          destPath: Uri.Path
      ): IO[RejOr[ValidatedCopyFile]] = {

        val sourceBucketProtectedPath = basePath(config, sourceBucket)
        val destBucketProtectedPath   = basePath(config, destBucket)
        val absSourcePath             = filePath(config, sourceBucket, sourcePath)
        val absDestPath               = filePath(config, destBucket, destPath)

        def notFound = PathNotFound(destBucket, sourcePath)

        (for {
          _      <- rejectIf(fileExists(absSourcePath).map(!_), notFound)
          _      <- rejectIf((!descendantOf(absSourcePath, sourceBucketProtectedPath)).pure[IO], notFound)
          _      <- throwIf(!descendantOf(absDestPath, destBucketProtectedPath), PathInvalid(destBucket, destPath))
          _      <- rejectIf(fileExists(absDestPath), PathAlreadyExists(destBucket, destPath))
          isFile <- EitherT.right[Rejection](isRegularFile(absSourcePath))
          _      <- throwIf(!isFile, PathInvalid(sourceBucket, sourcePath))
        } yield new ValidatedCopyFile(sourceBucket, destBucket, absSourcePath, absDestPath) {}).value
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
            ifTrue = rejectIfDirContainsLink(name, sourcePath, absSourcePath).as(true),
            ifFalse = EitherT.leftT(PathNotFound(name, sourcePath))
          )

      def rejectIfFileIsSymbolicLinkOrContainsHardLink(
          name: String,
          sourcePath: Uri.Path,
          absSourcePath: Path
      ): EitherT[IO, Rejection, Unit] =
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

      def rejectIfDirContainsLink(name: String, sourcePath: Uri.Path, path: Path): EitherT[IO, Rejection, Unit] =
        rejectIf(dirContainsLink(path), PathContainsLinks(name, sourcePath))

      def rejectIf(cond: IO[Boolean], rej: Rejection): EitherT[IO, Rejection, Unit] = EitherT(
        cond.ifF(Left(rej), Right(()))
      )

      def throwIf(cond: Boolean, e: StorageError): EitherT[IO, Rejection, Unit] = EitherT.right(throwIfIO(cond, e))

      def throwIfIO(cond: Boolean, e: StorageError): IO[Unit] = IO.raiseWhen(cond)(e)
    }
}
