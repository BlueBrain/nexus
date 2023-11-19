package ch.epfl.bluebrain.nexus.storage

import akka.http.scaladsl.model.Uri
import akka.stream.Materializer
import akka.stream.alpakka.file.scaladsl.Directory
import akka.stream.scaladsl.{FileIO, Keep}
import cats.effect.Effect
import cats.implicits._
import ch.epfl.bluebrain.nexus.storage.File._
import ch.epfl.bluebrain.nexus.storage.Rejection.PathNotFound
import ch.epfl.bluebrain.nexus.storage.StorageError.{InternalError, PathInvalid, PermissionsFixingFailed}
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
import scala.util.Try

trait Storages[F[_], Source] {

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
    *   The file attributes containing the metadata (bytes and location) wrapped in an F effect type
    */
  def createFile(
      name: String,
      path: Uri.Path,
      source: Source
  )(implicit bucketEv: BucketExists, pathEv: PathDoesNotExist): F[FileAttributes]

  def copyFile(
      name: String,
      sourcePath: Uri.Path,
      destPath: Uri.Path
  )(implicit bucketEv: BucketExists, pathEv: PathDoesNotExist): F[RejOr[Unit]]

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
    *   Left(rejection) or Right(fileAttributes). The file attributes contain the metadata (bytes and location) wrapped
    *   in an F effect type
    */
  def moveFile(
      name: String,
      sourcePath: Uri.Path,
      destPath: Uri.Path
  )(implicit bucketEv: BucketExists, pathEv: PathDoesNotExist): F[RejOrAttributes]

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
  )(implicit bucketEv: BucketExists, pathEv: PathExists): F[FileAttributes]

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
  final class DiskStorage[F[_]](
      config: StorageConfig,
      contentTypeDetector: ContentTypeDetector,
      digestConfig: DigestConfig,
      cache: AttributesCache[F]
  )(implicit
      ec: ExecutionContext,
      mt: Materializer,
      F: Effect[F]
  ) extends Storages[F, AkkaSource] {

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
    )(implicit bucketEv: BucketExists, pathEv: PathDoesNotExist): F[FileAttributes] = {
      val absFilePath = filePath(config, name, path)
      if (descendantOf(absFilePath, basePath(config, name)))
        F.fromTry(Try(Files.createDirectories(absFilePath.getParent))) >>
          F.fromTry(Try(MessageDigest.getInstance(digestConfig.algorithm))).flatMap { msgDigest =>
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
              .to[F]
          }
      else
        F.raiseError(PathInvalid(name, path))
    }

    def copyFile(
        name: String,
        sourcePath: Uri.Path,
        destPath: Uri.Path
    )(implicit bucketEv: BucketExists, pathEv: PathDoesNotExist): F[RejOr[Unit]] =
      CreateFileFromExisting.forCopyFromProtectedDir(config, name, sourcePath, destPath).flatMap {
        case Left(value)  => value.asLeft[Unit].pure[F]
        case Right(value) => doCopy(value.absSourcePath, value.absDestPath).map(Right(_))
      }

    def moveFile(
        name: String,
        sourcePath: Uri.Path,
        destPath: Uri.Path
    )(implicit bucketEv: BucketExists, pathEv: PathDoesNotExist): F[RejOrAttributes] =
      CreateFileFromExisting.forMoveIntoProtectedDir(config, name, sourcePath, destPath).flatMap {
        case Left(value)  => value.asLeft[FileAttributes].pure[F]
        case Right(value) =>
          fixPermissionsAndCopy(value.absSourcePath, value.absDestPath, isDir = value.isDir)
      }

    private def fixPermissionsAndCopy(absSourcePath: Path, absDestPath: Path, isDir: Boolean) =
      fixPermissions(absSourcePath) >>
        computeSizeAndMoveFile(absSourcePath, absDestPath, isDir)

    private def fixPermissions(path: Path): F[Unit] =
      if (config.fixerEnabled)
        for {
          absPath  <- F.delay(path.toAbsolutePath.normalize.toString)
          logger    = StringProcessLogger(config.fixerCommand, absPath)
          process   = Process(config.fixerCommand :+ absPath)
          exitCode <- F.delay(process ! logger)
          _        <- F.raiseUnless(exitCode == 0)(PermissionsFixingFailed(absPath, logger.toString))
        } yield ()
      else F.unit

    private def doCopy(
        absSourcePath: Path,
        absDestPath: Path
    ): F[Unit] =
      for {
        _ <- F.delay(Files.createDirectories(absDestPath.getParent))
        _ <- F.delay(Files.copy(absSourcePath, absDestPath, COPY_ATTRIBUTES))
        _ <- F.delay(cache.asyncComputePut(absDestPath, digestConfig.algorithm))
      } yield ()

    private def computeSizeAndMoveFile(
        absSourcePath: Path,
        absDestPath: Path,
        isDir: Boolean
    ): F[RejOrAttributes] =
      for {
        computedSize <- size(absSourcePath)
        _            <- F.delay(Files.createDirectories(absDestPath.getParent))
        _            <- F.delay(Files.move(absSourcePath, absDestPath, ATOMIC_MOVE))
        _            <- F.delay(cache.asyncComputePut(absDestPath, digestConfig.algorithm))
        mediaType    <- F.delay(contentTypeDetector(absDestPath, isDir))
      } yield Right(FileAttributes(absDestPath.toAkkaUri, computedSize, Digest.empty, mediaType))

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
    )(implicit bucketEv: BucketExists, pathEv: PathExists): F[FileAttributes] =
      cache.get(filePath(config, name, path))

    private def size(absPath: Path): F[Long] =
      if (Files.isDirectory(absPath))
        Directory.walk(absPath).filter(Files.isRegularFile(_)).runFold(0L)(_ + Files.size(_)).to[F]
      else if (Files.isRegularFile(absPath))
        F.delay(Files.size(absPath))
      else
        F.raiseError(InternalError(s"Path '$absPath' is not a file nor a directory"))
  }

}
