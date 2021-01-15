package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model

import akka.actor.ActorSystem
import akka.http.scaladsl.model.Uri
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.{ComputedFileAttributes, FileAttributes, FileDescription}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.Storages
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageValue.{DiskStorageValue, RemoteDiskStorageValue, S3StorageValue}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.StorageFileRejection.{FetchAttributeRejection, FetchFileRejection, MoveFileRejection, SaveFileRejection}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.disk.{DiskStorageFetchFile, DiskStorageSaveFile}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote.client.model.RemoteDiskStorageFileAttributes
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote.{RemoteDiskStorageFetchFile, RemoteDiskStorageLinkFile, RemoteDiskStorageSaveFile, RemoteStorageFetchAttributes}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3.{S3StorageFetchFile, S3StorageSaveFile}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.AkkaSource
import ch.epfl.bluebrain.nexus.delta.sdk.model.TagLabel
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import io.circe.syntax._
import io.circe.{Encoder, Json}
import monix.bio.IO
import monix.execution.Scheduler

sealed trait Storage extends Product with Serializable {

  /**
    * @return the view id
    */
  def id: Iri

  /**
    * @return a reference to the project that the storage belongs to
    */
  def project: ProjectRef

  /**
    * @return the tag -> rev mapping
    */
  def tags: Map[TagLabel, Long]

  /**
    * @return the original json document provided at creation or update
    */
  def source: Secret[Json]

  /**
    * @return ''true'' if this store is the project's default, ''false'' otherwise
    */
  def default: Boolean

  /**
    * Fetch a file using the current storage with the passed ''attributes''
    *
    * @param attributes the attributes of the file to fetch
    */
  def fetchFile(
      attributes: FileAttributes
  )(implicit as: ActorSystem, sc: Scheduler): IO[FetchFileRejection, AkkaSource]

  /**
    * Fetch a file computed attributes
    *
    * @param attributes the current attributes
    */
  def fetchComputedAttributes(
      attributes: FileAttributes
  )(implicit as: ActorSystem, sc: Scheduler): IO[FetchAttributeRejection, ComputedFileAttributes]

  /**
    * Save a file using the current storage.
    *
    * @param description the file description metadata
    * @param source      the file content
    */
  def saveFile(
      description: FileDescription,
      source: AkkaSource
  )(implicit as: ActorSystem, sc: Scheduler): IO[SaveFileRejection, FileAttributes]

  /**
    * Moves a file using the current storage
    *
    * @param sourcePath  the location of the file to be moved
    * @param description the end location of the file with its metadata
    */
  def moveFile(
      sourcePath: Uri.Path,
      description: FileDescription
  )(implicit as: ActorSystem, sc: Scheduler): IO[MoveFileRejection, FileAttributes]

  /**
    * @return the storage type
    */
  def tpe: StorageType = storageValue.tpe

  def storageValue: StorageValue
}

object Storage {

  /**
    * A storage that stores and fetches files from a local volume
    */
  final case class DiskStorage(
      id: Iri,
      project: ProjectRef,
      value: DiskStorageValue,
      tags: Map[TagLabel, Long],
      source: Secret[Json]
  ) extends Storage {
    override val default: Boolean           = value.default
    override val storageValue: StorageValue = value

    override def fetchFile(
        attributes: FileAttributes
    )(implicit as: ActorSystem, sc: Scheduler): IO[FetchFileRejection, AkkaSource] =
      DiskStorageFetchFile(attributes.location.path)

    override def saveFile(
        description: FileDescription,
        source: AkkaSource
    )(implicit as: ActorSystem, sc: Scheduler): IO[SaveFileRejection, FileAttributes] =
      new DiskStorageSaveFile(this).apply(description, source)

    override def moveFile(
        sourcePath: Uri.Path,
        description: FileDescription
    )(implicit as: ActorSystem, sc: Scheduler): IO[MoveFileRejection, FileAttributes] =
      IO.raiseError(MoveFileRejection.UnsupportedOperation(StorageType.DiskStorage))

    override def fetchComputedAttributes(
        attributes: FileAttributes
    )(implicit as: ActorSystem, sc: Scheduler): IO[FetchAttributeRejection, ComputedFileAttributes] =
      IO.raiseError(FetchAttributeRejection.UnsupportedOperation(StorageType.DiskStorage))

  }

  /**
    * A storage that stores and fetches files from an S3 compatible service
    */
  final case class S3Storage(
      id: Iri,
      project: ProjectRef,
      value: S3StorageValue,
      tags: Map[TagLabel, Long],
      source: Secret[Json]
  ) extends Storage {

    override val default: Boolean           = value.default
    override val storageValue: StorageValue = value

    override def fetchFile(
        attributes: FileAttributes
    )(implicit as: ActorSystem, sc: Scheduler): IO[FetchFileRejection, AkkaSource] =
      new S3StorageFetchFile(value).apply(attributes.path)

    override def saveFile(
        description: FileDescription,
        source: AkkaSource
    )(implicit as: ActorSystem, sc: Scheduler): IO[SaveFileRejection, FileAttributes] =
      new S3StorageSaveFile(this).apply(description, source)

    override def moveFile(
        sourcePath: Uri.Path,
        description: FileDescription
    )(implicit as: ActorSystem, sc: Scheduler): IO[MoveFileRejection, FileAttributes] =
      IO.raiseError(MoveFileRejection.UnsupportedOperation(StorageType.S3Storage))

    override def fetchComputedAttributes(
        attributes: FileAttributes
    )(implicit as: ActorSystem, sc: Scheduler): IO[FetchAttributeRejection, ComputedFileAttributes] =
      IO.raiseError(FetchAttributeRejection.UnsupportedOperation(StorageType.S3Storage))
  }

  /**
    * A storage that stores and fetches files from a remote volume using a well-defined API
    */
  final case class RemoteDiskStorage(
      id: Iri,
      project: ProjectRef,
      value: RemoteDiskStorageValue,
      tags: Map[TagLabel, Long],
      source: Secret[Json]
  ) extends Storage {
    override val default: Boolean           = value.default
    override val storageValue: StorageValue = value

    override def fetchFile(
        attributes: FileAttributes
    )(implicit as: ActorSystem, sc: Scheduler): IO[FetchFileRejection, AkkaSource] =
      new RemoteDiskStorageFetchFile(value).apply(attributes.path)

    override def saveFile(
        description: FileDescription,
        source: AkkaSource
    )(implicit as: ActorSystem, sc: Scheduler): IO[SaveFileRejection, FileAttributes] =
      new RemoteDiskStorageSaveFile(this).apply(description, source)

    override def moveFile(
        sourcePath: Uri.Path,
        description: FileDescription
    )(implicit as: ActorSystem, sc: Scheduler): IO[MoveFileRejection, FileAttributes] =
      new RemoteDiskStorageLinkFile(this).apply(sourcePath, description)

    override def fetchComputedAttributes(
        attributes: FileAttributes
    )(implicit as: ActorSystem, sc: Scheduler): IO[FetchAttributeRejection, ComputedFileAttributes] =
      new RemoteStorageFetchAttributes(value).apply(attributes.path).map {
        case RemoteDiskStorageFileAttributes(_, bytes, digest, mediaType) =>
          ComputedFileAttributes(mediaType, bytes, digest)
      }
  }

  private val secretFields = List("credentials", "accessKey", "secretKey")

  private def getOptionalKeyValue(key: String, json: Json) =
    json.hcursor.get[Option[String]](key).getOrElse(None).map(key -> _)

  def encryptSource(json: Secret[Json], crypto: Crypto): Either[String, Json] = {
    def getField(key: String) = getOptionalKeyValue(key, json.value)

    secretFields.flatMap(getField).foldM(json.value) { case (acc, (key, value)) =>
      crypto.encrypt(value).map(encrypted => acc deepMerge Json.obj(key -> encrypted.asJson))
    }
  }

  def decryptSource(json: Json, crypto: Crypto): Either[String, Secret[Json]] = {
    def getField(key: String) = getOptionalKeyValue(key, json)

    secretFields
      .flatMap(getField)
      .foldM(json) { case (acc, (key, value)) =>
        crypto.decrypt(value).map(encrypted => acc deepMerge Json.obj(key -> encrypted.asJson))
      }
      .map(Secret.apply)
  }

  implicit private[storages] val storageEncoder: Encoder.AsObject[Storage] =
    Encoder.encodeJsonObject.contramapObject { s =>
      s.storageValue.asJsonObject.addContext(s.source.value.topContextValueOrEmpty.contextObj)
    }

  implicit val storageJsonLdEncoder: JsonLdEncoder[Storage] = JsonLdEncoder.computeFromCirce(_.id, Storages.context)
}
