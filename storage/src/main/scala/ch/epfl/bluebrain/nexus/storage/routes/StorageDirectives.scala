package ch.epfl.bluebrain.nexus.storage.routes

import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.model.Uri.Path
import akka.http.scaladsl.model.Uri.Path._
import akka.http.scaladsl.server.Directives.{extractUnmatchedPath, failWith, pass, provide, reject}
import akka.http.scaladsl.server._
import cats.data.NonEmptyList
import ch.epfl.bluebrain.nexus.storage.Rejection.{BucketNotFound, PathAlreadyExists, PathNotFound}
import ch.epfl.bluebrain.nexus.storage.StorageError.PathInvalid
import ch.epfl.bluebrain.nexus.storage.Storages
import ch.epfl.bluebrain.nexus.storage.Storages.PathExistence.{PathDoesNotExist, PathExists}
import ch.epfl.bluebrain.nexus.storage.Storages.BucketExistence.BucketExists

import scala.annotation.tailrec

object StorageDirectives {

  /**
    * Extracts the path from the unmatched segments
    *
    * @param name
    *   the storage bucket name
    */
  def extractPath(name: String): Directive1[Path] =
    extractUnmatchedPath.flatMap(p => validatePath(name, p).tmap(_ => relativize(p)))

  private def pathInvalid(path: Path): Boolean =
    path.toString.contains("//") || containsRelativeChar(path)

  /**
    * Validates if the path is correct or malformed
    *
    * @param name
    *   the storage bucket name
    * @param path
    *   the path to validate
    */
  def validatePath(name: String, path: Path): Directive0 =
    if (pathInvalid(path)) failWith(PathInvalid(name, path)) else pass

  def validatePaths(pathsByBucket: NonEmptyList[(String, Path)]): Directive0 =
    pathsByBucket
      .collectFirst[Directive0] {
        case (bucket, p) if pathInvalid(p) =>
          failWith(PathInvalid(bucket, p))
      }
      .getOrElse(pass)

  @tailrec
  private def containsRelativeChar(path: Path): Boolean =
    path match {
      case Path.Empty                                      => false
      case Segment(head, _) if head == "." || head == ".." => true
      case _                                               => containsRelativeChar(path.tail)
    }

  /**
    * Returns the evidence that a storage bucket exists
    *
    * @param name
    *   the storage bucket name
    * @param storages
    *   the storages bundle api
    * @return
    *   BucketExists when the storage bucket exists, rejection otherwise
    */
  def bucketExists(name: String)(implicit storages: Storages[_]): Directive1[BucketExists] =
    storages.exists(name) match {
      case exists: BucketExists => provide(exists)
      case _                    => reject(BucketNotFound(name))
    }

  def bucketsExist(buckets: NonEmptyList[String])(implicit storages: Storages[_]): Directive1[BucketExists] =
    buckets
      .map(storages.exists)
      .zip(buckets)
      .collectFirst[Directive1[BucketExists]] { case (e, bucket) if !e.exists => reject(BucketNotFound(bucket)) }
      .getOrElse(provide(BucketExists))

  /**
    * Returns the evidence that a path exists
    *
    * @param name
    *   the storage bucket name
    * @param path
    *   the path location
    * @param storages
    *   the storages bundle api
    * @return
    *   PathExists when the path exists inside the bucket, rejection otherwise
    */
  def pathExists(name: String, path: Uri.Path)(implicit
      storages: Storages[_]
  ): Directive1[PathExists] =
    storages.pathExists(name, path) match {
      case exists: PathExists => provide(exists)
      case _                  => reject(PathNotFound(name, path))
    }

  /**
    * Returns the evidence that a path does not exist
    *
    * @param name
    *   the storage bucket name
    * @param path
    *   the path location
    * @param storages
    *   the storages bundle api
    * @return
    *   PathDoesNotExist when the path does not exist inside the bucket, rejection otherwise
    */
  def pathNotExists(name: String, path: Uri.Path)(implicit
      storages: Storages[_]
  ): Directive1[PathDoesNotExist] =
    storages.pathExists(name, path) match {
      case notExists: PathDoesNotExist => provide(notExists)
      case _                           => reject(PathAlreadyExists(name, path))
    }

  def pathsDoNotExist(name: String, paths: NonEmptyList[Uri.Path])(implicit
      storages: Storages[_]
  ): Directive1[PathDoesNotExist] =
    paths
      .collectFirst[Directive1[PathDoesNotExist]] {
        case p if storages.pathExists(name, p).exists =>
          reject(PathAlreadyExists(name, p))
      }
      .getOrElse(provide(PathDoesNotExist))

  /**
    * Extracts the relative file path from the unmatched segments
    */
  def extractRelativeFilePath(name: String): Directive1[Path] =
    extractPath(name).flatMap {
      case path if path.reverse.startsWithSegment => provide(path)
      case path                                   => failWith(PathInvalid(name, path))
    }

  @tailrec
  private def relativize(path: Path): Path =
    path match {
      case Slash(rest) => relativize(rest)
      case rest        => rest
    }
}
