package ch.epfl.bluebrain.nexus.storage

import akka.http.scaladsl.model.Uri
import akka.stream.Materializer
import akka.stream.alpakka.file.scaladsl.Directory
import akka.stream.scaladsl.{FileIO, Keep}
import cats.data.{EitherT, NonEmptyList}
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.utils.{CopyBetween, CopyFiles}
import ch.epfl.bluebrain.nexus.storage.File._
import ch.epfl.bluebrain.nexus.storage.Rejection.PathNotFound
import ch.epfl.bluebrain.nexus.storage.StorageError.{InternalError, PermissionsFixingFailed}
import ch.epfl.bluebrain.nexus.storage.Storages.BucketExistence._
import ch.epfl.bluebrain.nexus.storage.Storages.PathExistence._
import ch.epfl.bluebrain.nexus.storage.Storages.{BucketExistence, PathExistence}
import ch.epfl.bluebrain.nexus.storage.attributes.AttributesComputation._
import ch.epfl.bluebrain.nexus.storage.attributes.{AttributesCache, ContentTypeDetector}
import ch.epfl.bluebrain.nexus.storage.config.AppConfig.{DigestConfig, StorageConfig}
import ch.epfl.bluebrain.nexus.storage.files.{CopyFileOutput, ValidateFile}
import ch.epfl.bluebrain.nexus.storage.routes.CopyFile

import java.nio.file.StandardCopyOption._
import java.nio.file.{Files, Path}
import fs2.io.file.{Path => Fs2Path}
import java.security.MessageDigest
import scala.concurrent.{ExecutionContext, Future}
import scala.sys.process._

trait Storages[Source] {

  /**
    * Checks that the provided bucket name exists and it is readable/writable.
    *
    * @param name
    *   the storage bucket name
    */
  def exists(name: String): BucketExistence

  /**
    * Check whether the provided path already exists.
    *
    * @param name
    *   the storage bucket name
    * @param path
    *   the path location
    */
  def pathExists(name: String, path: Uri.Path): PathExistence

  /**
    * Creates a file with the provided ''metadata'' and ''source'' on the provided ''filePath''.
    *
    * @param name
    *   the storage bucket name
    * @param path
    *   the path location
    * @param source
    *   the file content
    * @return
    *   The file attributes containing the metadata (bytes and location)
    */
  def createFile(
      name: String,
      path: Uri.Path,
      source: Source
  )(implicit bucketEv: BucketExists, pathEv: PathDoesNotExist): IO[FileAttributes]

  /**
    * Copy files between locations inside the nexus folder. Attributes are neither recomputed nor fetched; it's assumed
    * clients already have this information from the source files.
    *
    * @param name
    *   the storage bucket name
    * @param files
    *   a list of source/destination files. The source files should exist under the nexus folder, and the destination
    *   files will be created there. Both must be files, not directories.
    */
  def copyFiles(
      name: String,
      files: NonEmptyList[CopyFile]
  )(implicit bucketEv: BucketExists, pathEv: PathDoesNotExist): IO[RejOr[NonEmptyList[CopyFileOutput]]]

  /**
    * Moves a path from the provided ''sourcePath'' to ''destPath'' inside the nexus folder.
    *
    * @param name
    *   the storage bucket name
    * @param sourcePath
    *   the source path location
    * @param destPath
    *   the destination path location inside the nexus folder
    * @return
    *   Left(rejection) or Right(fileAttributes). The file attributes contain the metadata (bytes and location)
    */
  def moveFile(
      name: String,
      sourcePath: Uri.Path,
      destPath: Uri.Path
  )(implicit bucketEv: BucketExists): IO[RejOrAttributes]

  /**
    * Retrieves the file as a Source.
    *
    * @param name
    *   the storage bucket name
    * @param path
    *   the path to the file location
    * @return
    *   Left(rejection), Right(source, Some(filename)) when the path is a file and Right(source, None) when the path is
    *   a directory
    */
  def getFile(
      name: String,
      path: Uri.Path
  )(implicit bucketEv: BucketExists, pathEv: PathExists): RejOr[(Source, Option[String])]

  /**
    * Retrieves the attributes of the file.
    *
    * @param name
    *   the storage bucket name
    * @param path
    *   the path to the file location
    */
  def getAttributes(
      name: String,
      path: Uri.Path
  )(implicit bucketEv: BucketExists, pathEv: PathExists): IO[FileAttributes]

}

object Storages {

  sealed trait BucketExistence {
    def exists: Boolean
  }
  sealed trait PathExistence   {
    def exists: Boolean
  }

  object BucketExistence {
    final case object BucketExists       extends BucketExistence {
      val exists = true
    }
    final case object BucketDoesNotExist extends BucketExistence {
      val exists = false
    }
    type BucketExists = BucketExists.type
    type BucketDoesNotExist = BucketDoesNotExist.type
  }

  object PathExistence {
    final case object PathExists       extends PathExistence {
      val exists = true
    }
    final case object PathDoesNotExist extends PathExistence {
      val exists = false
    }
    type PathExists = PathExists.type
    type PathDoesNotExist = PathDoesNotExist.type
  }

  /**
    * An Disk implementation of Storage interface.
    */
  final class DiskStorage(
      config: StorageConfig,
      contentTypeDetector: ContentTypeDetector,
      digestConfig: DigestConfig,
      cache: AttributesCache,
      validateFile: ValidateFile,
      copyFiles: CopyFiles
  )(implicit
      ec: ExecutionContext,
      mt: Materializer
  ) extends Storages[AkkaSource] {

    def exists(name: String): BucketExistence = {
      val path = basePath(config, name)
      if (path.getParent.getParent != config.rootVolume) BucketDoesNotExist
      else if (Files.isDirectory(path) && Files.isReadable(path)) BucketExists
      else BucketDoesNotExist
    }

    def pathExists(name: String, path: Uri.Path): PathExistence = {
      val absPath = filePath(config, name, path)
      if (Files.exists(absPath) && Files.isReadable(absPath) && descendantOf(absPath, basePath(config, name)))
        PathExists
      else PathDoesNotExist
    }

    def createFile(
        name: String,
        path: Uri.Path,
        source: AkkaSource
    )(implicit bucketEv: BucketExists, pathEv: PathDoesNotExist): IO[FileAttributes] =
      for {
        validated  <- validateFile.forCreate(name, path)
        _          <- IO.blocking(Files.createDirectories(validated.absDestPath.getParent))
        msgDigest  <- IO.delay(MessageDigest.getInstance(digestConfig.algorithm))
        attributes <- streamFileContents(source, path, validated.absDestPath, msgDigest)
      } yield attributes

    private def streamFileContents(
        source: AkkaSource,
        path: Uri.Path,
        absFilePath: Path,
        msgDigest: MessageDigest
    ): IO[FileAttributes] = {
      IO.fromFuture {
        IO.delay {
          source
            .alsoToMat(sinkDigest(msgDigest))(Keep.right)
            .toMat(FileIO.toPath(absFilePath)) { case (digFuture, ioFuture) =>
              digFuture.zipWith(ioFuture) {
                case (digest, io) if absFilePath.toFile.exists() =>
                  Future(FileAttributes(absFilePath.toAkkaUri, io.count, digest, contentTypeDetector(absFilePath)))
                case _                                           =>
                  Future.failed(InternalError(s"I/O error writing file to path '$path'"))
              }
            }
            .run()
            .flatten
        }
      }
    }

    def moveFile(
        name: String,
        sourcePath: Uri.Path,
        destPath: Uri.Path
    )(implicit bucketEv: BucketExists): IO[Either[Rejection, FileAttributes]] = (for {
      value <- EitherT(validateFile.forMoveIntoProtectedDir(name, sourcePath, destPath))
      attr  <- EitherT.right[Rejection](fixPermissionsAndCopy(value.absSourcePath, value.absDestPath, value.isDir))
    } yield attr).value

    private def fixPermissionsAndCopy(absSourcePath: Path, absDestPath: Path, isDir: Boolean) =
      fixPermissions(absSourcePath) >>
        computeSizeAndMoveFile(absSourcePath, absDestPath, isDir)

    private def fixPermissions(path: Path): IO[Unit] =
      if (config.fixerEnabled) {
        val absPath = path.toAbsolutePath.normalize.toString
        val logger  = StringProcessLogger(config.fixerCommand, absPath)
        val process = Process(config.fixerCommand :+ absPath)

        for {
          exitCode <- IO.blocking(process ! logger)
          _        <- IO.raiseUnless(exitCode == 0)(PermissionsFixingFailed(absPath, logger.toString))
        } yield ()
      } else IO.unit

    private def computeSizeAndMoveFile(
        absSourcePath: Path,
        absDestPath: Path,
        isDir: Boolean
    ): IO[FileAttributes] =
      for {
        computedSize <- size(absSourcePath)
        _            <- IO.blocking(Files.createDirectories(absDestPath.getParent))
        _            <- IO.blocking(Files.move(absSourcePath, absDestPath, ATOMIC_MOVE))
        _            <- IO.delay(cache.asyncComputePut(absDestPath, digestConfig.algorithm))
        mediaType    <- IO.blocking(contentTypeDetector(absDestPath, isDir))
      } yield FileAttributes(absDestPath.toAkkaUri, computedSize, Digest.empty, mediaType)

    private def size(absPath: Path): IO[Long] =
      if (Files.isDirectory(absPath)) {
        IO.fromFuture(IO.delay(Directory.walk(absPath).filter(Files.isRegularFile(_)).runFold(0L)(_ + Files.size(_))))
      } else if (Files.isRegularFile(absPath))
        IO.blocking(Files.size(absPath))
      else
        IO.raiseError(InternalError(s"Path '$absPath' is not a file nor a directory"))

    def copyFiles(
        destBucket: String,
        files: NonEmptyList[CopyFile]
    )(implicit bucketEv: BucketExists, pathEv: PathDoesNotExist): IO[RejOr[NonEmptyList[CopyFileOutput]]] =
      (for {
        validated  <-
          files.traverse(f =>
            EitherT(validateFile.forCopyWithinProtectedDir(f.sourceBucket, destBucket, f.source, f.destination))
          )
        copyBetween =
          validated.map(v => CopyBetween(Fs2Path.fromNioPath(v.absSourcePath), Fs2Path.fromNioPath(v.absDestPath)))
        _          <- EitherT.right[Rejection](copyFiles.copyAll(copyBetween))
      } yield files.zip(validated).map { case (raw, valid) =>
        CopyFileOutput(raw.source, raw.destination, valid.absSourcePath, valid.absDestPath)
      }).value

    def getFile(
        name: String,
        path: Uri.Path
    )(implicit bucketEv: BucketExists, pathEv: PathExists): RejOr[(AkkaSource, Option[String])] = {
      val absPath = filePath(config, name, path)
      if (Files.isRegularFile(absPath)) Right(fileSource(absPath) -> Some(absPath.getFileName.toString))
      else if (Files.isDirectory(absPath)) Right(folderSource(absPath) -> None)
      else Left(PathNotFound(name, path))
    }

    def getAttributes(
        name: String,
        path: Uri.Path
    )(implicit bucketEv: BucketExists, pathEv: PathExists): IO[FileAttributes] =
      cache.get(filePath(config, name, path))

  }

}
