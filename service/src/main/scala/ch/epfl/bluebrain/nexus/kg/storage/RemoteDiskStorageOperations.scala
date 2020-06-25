package ch.epfl.bluebrain.nexus.kg.storage

import akka.http.scaladsl.model.Uri
import cats.Applicative
import cats.effect.Effect
import cats.implicits._
import ch.epfl.bluebrain.nexus.iam.auth.AccessToken
import ch.epfl.bluebrain.nexus.iam.client.types.AuthToken
import ch.epfl.bluebrain.nexus.kg.resources.ResId
import ch.epfl.bluebrain.nexus.kg.resources.file.File._
import ch.epfl.bluebrain.nexus.kg.storage.Storage._
import ch.epfl.bluebrain.nexus.storage.client.StorageClient
import ch.epfl.bluebrain.nexus.storage.client.types.FileAttributes.{Digest => StorageDigest}
import ch.epfl.bluebrain.nexus.storage.client.types.{FileAttributes => StorageFileAttributes}

object RemoteDiskStorageOperations {

  // TODO: Remove when migrating ADMIN client
  implicit private def oldTokenConversion(implicit token: Option[AccessToken]): Option[AuthToken] =
    token.map(t => AuthToken(t.value))

  implicit private def toDigest(digest: StorageDigest): Digest = Digest(digest.algorithm, digest.value)

  /**
    * [[VerifyStorage]] implementation for [[RemoteDiskStorage]]
    *
    * @param storage the [[RemoteDiskStorage]]
    * @param client  the remote storage client
    */
  final class Verify[F[_]: Applicative](storage: RemoteDiskStorage, client: StorageClient[F]) extends VerifyStorage[F] {
    implicit val cred = storage.credentials.map(AccessToken)

    override def apply: F[Either[String, Unit]] =
      client.exists(storage.folder).map {
        case true  => Right(())
        case false => Left(s"Folder '${storage.folder}' does not exists on the endpoint '${storage.endpoint}'")
      }
  }

  /**
    * [[FetchFile]] implementation for [[RemoteDiskStorage]]
    *
    * @param storage the [[RemoteDiskStorage]]
    * @param client  the remote storage client
    */
  final class Fetch[F[_]](storage: RemoteDiskStorage, client: StorageClient[F]) extends FetchFile[F, AkkaSource] {
    implicit val cred = storage.credentials.map(AccessToken)

    override def apply(fileMeta: FileAttributes): F[AkkaSource] =
      client.getFile(storage.folder, fileMeta.path)

  }

  /**
    * [[SaveFile]] implementation for [[RemoteDiskStorage]]
    *
    * @param storage the [[RemoteDiskStorage]]
    * @param client  the remote storage client
    */
  final class Save[F[_]: Effect](storage: RemoteDiskStorage, client: StorageClient[F]) extends SaveFile[F, AkkaSource] {
    implicit val cred = storage.credentials.map(AccessToken)

    override def apply(id: ResId, fileDesc: FileDescription, source: AkkaSource): F[FileAttributes] = {
      val relativePath = Uri.Path(mangle(storage.ref, fileDesc.uuid, fileDesc.filename))
      client.createFile(storage.folder, relativePath, source).map {
        case StorageFileAttributes(location, bytes, dig, mediaType) =>
          val usedMediaType = fileDesc.mediaType.getOrElse(mediaType)
          FileAttributes(fileDesc.uuid, location, relativePath, fileDesc.filename, usedMediaType, bytes, dig)
      }
    }
  }

  /**
    * [[LinkFile]] implementation for [[RemoteDiskStorage]]
    *
    * @param storage the [[RemoteDiskStorage]]
    * @param client  the remote storage client
    */
  final class Link[F[_]: Effect](storage: RemoteDiskStorage, client: StorageClient[F]) extends LinkFile[F] {
    implicit val cred = storage.credentials.map(AccessToken)

    override def apply(id: ResId, fileDesc: FileDescription, path: Uri.Path): F[FileAttributes] = {
      val destRelativePath = Uri.Path(mangle(storage.ref, fileDesc.uuid, fileDesc.filename))
      client.moveFile(storage.folder, path, destRelativePath).map {
        case StorageFileAttributes(location, bytes, dig, mediaType) =>
          val usedMediaType = fileDesc.mediaType.getOrElse(mediaType)
          FileAttributes(fileDesc.uuid, location, destRelativePath, fileDesc.filename, usedMediaType, bytes, dig)
      }
    }
  }

  /**
    * [[FetchFileAttributes]] implementation for [[RemoteDiskStorage]]
    *
    * @param storage the [[RemoteDiskStorage]]
    * @param client  the remote storage client
    */
  final class FetchAttributes[F[_]](storage: RemoteDiskStorage, client: StorageClient[F])
      extends FetchFileAttributes[F] {
    implicit val cred = storage.credentials.map(AccessToken)

    override def apply(relativePath: Uri.Path): F[StorageFileAttributes] =
      client.getAttributes(storage.folder, relativePath)
  }

}
