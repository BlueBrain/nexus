package ch.epfl.bluebrain.nexus.storage

import akka.http.scaladsl.model.Uri
import akka.stream.Materializer
import akka.stream.alpakka.file.scaladsl.Directory
import akka.stream.scaladsl.{FileIO, Keep}
import cats.effect.IO
import cats.implicits._
import ch.epfl.bluebrain.nexus.storage.File._
import ch.epfl.bluebrain.nexus.storage.Rejection.PathNotFound
import ch.epfl.bluebrain.nexus.storage.StorageError.{InternalError, PermissionsFixingFailed}
import ch.epfl.bluebrain.nexus.storage.Storages.BucketExistence._
import ch.epfl.bluebrain.nexus.storage.Storages.PathExistence._
import ch.epfl.bluebrain.nexus.storage.Storages.{BucketExistence, PathExistence}
import ch.epfl.bluebrain.nexus.storage.attributes.AttributesComputation._
import ch.epfl.bluebrain.nexus.storage.attributes.{AttributesCache, ContentTypeDetector}
import ch.epfl.bluebrain.nexus.storage.config.AppConfig.{DigestConfig, StorageConfig}

import java.nio.file.StandardCopyOption._
import java.nio.file.{Files, Path}
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
    * Copies a file between locations inside the nexus folder. Attributes are neither recomputed nor fetched; this
    * method is called only for its effects.
    *
    * @param name
    *   the storage bucket name
    * @param sourcePath
    *   the source path location within the nexus folder. Must be a file, not a directory
    * @param destPath
    *   the destination path location within the nexus folder
    */
  def copyFile(
      name: String,
      sourcePath: Uri.Path,
      destPath: Uri.Path
  )(implicit bucketEv: BucketExists, pathEv: PathDoesNotExist): IO[RejOr[Unit]]

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

  sealed trait BucketExistence
  sealed trait PathExistence

  object BucketExistence {
    final case object BucketExists       extends BucketExistence
    final case object BucketDoesNotExist extends BucketExistence
    type BucketExists       = BucketExists.type
    type BucketDoesNotExist = BucketDoesNotExist.type
  }

  object PathExistence {
    final case object PathExists       extends PathExistence
    final case object PathDoesNotExist extends PathExistence
    type PathExists       = PathExists.type
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
      validateFile: ValidateFile
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
        _          <- IO.delay(Files.createDirectories(validated.absDestPath.getParent))
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
    )(implicit bucketEv: BucketExists): IO[RejOrAttributes] =
      validateFile.forMoveIntoProtectedDir(name, sourcePath, destPath).flatMap {
        case Left(value)  => value.asLeft[FileAttributes].pure[IO]
        case Right(value) =>
          fixPermissionsAndCopy(value.absSourcePath, value.absDestPath, isDir = value.isDir)
      }

    private def fixPermissionsAndCopy(absSourcePath: Path, absDestPath: Path, isDir: Boolean) =
      fixPermissions(absSourcePath) >>
        computeSizeAndMoveFile(absSourcePath, absDestPath, isDir)

    private def fixPermissions(path: Path): IO[Unit] =
      if (config.fixerEnabled)
        for {
          absPath  <- IO.delay(path.toAbsolutePath.normalize.toString)
          logger    = StringProcessLogger(config.fixerCommand, absPath)
          process   = Process(config.fixerCommand :+ absPath)
          exitCode <- IO.delay(process ! logger)
          _        <- IO.raiseUnless(exitCode == 0)(PermissionsFixingFailed(absPath, logger.toString))
        } yield ()
      else IO.unit

    private def computeSizeAndMoveFile(
        absSourcePath: Path,
        absDestPath: Path,
        isDir: Boolean
    ): IO[RejOrAttributes] =
      for {
        computedSize <- size(absSourcePath)
        _            <- IO.delay(Files.createDirectories(absDestPath.getParent))
        _            <- IO.delay(Files.move(absSourcePath, absDestPath, ATOMIC_MOVE))
        _            <- IO.delay(cache.asyncComputePut(absDestPath, digestConfig.algorithm))
        mediaType    <- IO.delay(contentTypeDetector(absDestPath, isDir))
      } yield Right(FileAttributes(absDestPath.toAkkaUri, computedSize, Digest.empty, mediaType))

    private def size(absPath: Path): IO[Long] =
      if (Files.isDirectory(absPath)) {
        IO.fromFuture(IO.delay(Directory.walk(absPath).filter(Files.isRegularFile(_)).runFold(0L)(_ + Files.size(_))))
      } else if (Files.isRegularFile(absPath))
        IO.delay(Files.size(absPath))
      else
        IO.raiseError(InternalError(s"Path '$absPath' is not a file nor a directory"))

    def copyFile(
        name: String,
        sourcePath: Uri.Path,
        destPath: Uri.Path
    )(implicit bucketEv: BucketExists, pathEv: PathDoesNotExist): IO[RejOr[Unit]] =
      validateFile.forCopyWithinProtectedDir(name, sourcePath, destPath).flatMap {
        case Left(v)  => v.asLeft[Unit].pure[IO]
        case Right(v) =>
          for {
            _ <- IO.delay(Files.createDirectories(v.absDestPath.getParent))
            _ <- IO.delay(Files.copy(v.absSourcePath, v.absDestPath, COPY_ATTRIBUTES))
            _ <- IO.delay(cache.asyncComputePut(v.absDestPath, digestConfig.algorithm))
          } yield Right(())
      }

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
