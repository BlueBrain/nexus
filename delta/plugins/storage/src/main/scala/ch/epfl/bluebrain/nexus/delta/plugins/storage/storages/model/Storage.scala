package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model

import akka.actor.ActorSystem
import akka.http.scaladsl.model.Uri
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.{FileAttributes, FileDescription}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.contexts
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageValue.{DiskStorageValue, RemoteDiskStorageValue, S3StorageValue}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.StorageFileRejection.{FetchFileRejection, MoveFileRejection, SaveFileRejection}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.disk.{DiskStorageFetchFile, DiskStorageMoveFile, DiskStorageSaveFile}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote.{RemoteDiskStorageFetchFile, RemoteDiskStorageMoveFile, RemoteDiskStorageSaveFile}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3.{S3StorageFetchFile, S3StorageMoveFile, S3StorageSaveFile}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.model.TagLabel
import ch.epfl.bluebrain.nexus.delta.sdk.{AkkaSource, Mapper}
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
  def fetchFile[R](
      attributes: FileAttributes
  )(implicit mapper: Mapper[FetchFileRejection, R], as: ActorSystem, sc: Scheduler): IO[R, AkkaSource]

  /**
    * Save a file using the current storage.
    *
    * @param description the file description metadata
    * @param source      the file content
    */
  def saveFile[R](
      description: FileDescription,
      source: AkkaSource
  )(implicit mapper: Mapper[SaveFileRejection, R], as: ActorSystem, sc: Scheduler): IO[R, FileAttributes]

  /**
    * Moves a file using the current storage
    *
    * @param sourcePath  the location of the file to be moved
    * @param description the end location of the file with its metadata
    */
  def moveFile[R](
      sourcePath: Uri.Path,
      description: FileDescription
  )(implicit mapper: Mapper[MoveFileRejection, R], as: ActorSystem, sc: Scheduler): IO[R, FileAttributes]

  private[model] def storageValue: StorageValue

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

    override def fetchFile[R](
        attributes: FileAttributes
    )(implicit mapper: Mapper[FetchFileRejection, R], as: ActorSystem, sc: Scheduler): IO[R, AkkaSource] =
      DiskStorageFetchFile(attributes.location.path).leftMap(mapper.to)

    override def saveFile[R](
        description: FileDescription,
        source: AkkaSource
    )(implicit mapper: Mapper[SaveFileRejection, R], as: ActorSystem, sc: Scheduler): IO[R, FileAttributes] =
      new DiskStorageSaveFile(this).apply(description, source).leftMap(mapper.to)

    override def moveFile[R](
        sourcePath: Uri.Path,
        description: FileDescription
    )(implicit mapper: Mapper[MoveFileRejection, R], as: ActorSystem, sc: Scheduler): IO[R, FileAttributes] =
      DiskStorageMoveFile(sourcePath, description).leftMap(mapper.to)
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

    override def fetchFile[R](
        attributes: FileAttributes
    )(implicit mapper: Mapper[FetchFileRejection, R], as: ActorSystem, sc: Scheduler): IO[R, AkkaSource] =
      new S3StorageFetchFile(value).apply(attributes.path).leftMap(mapper.to)

    override def saveFile[R](
        description: FileDescription,
        source: AkkaSource
    )(implicit mapper: Mapper[SaveFileRejection, R], as: ActorSystem, sc: Scheduler): IO[R, FileAttributes] =
      new S3StorageSaveFile(this).apply(description, source).leftMap(mapper.to)

    override def moveFile[R](
        sourcePath: Uri.Path,
        description: FileDescription
    )(implicit mapper: Mapper[MoveFileRejection, R], as: ActorSystem, sc: Scheduler): IO[R, FileAttributes] =
      S3StorageMoveFile(sourcePath, description).leftMap(mapper.to)
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

    override def fetchFile[R](
        attributes: FileAttributes
    )(implicit mapper: Mapper[FetchFileRejection, R], as: ActorSystem, sc: Scheduler): IO[R, AkkaSource] =
      new RemoteDiskStorageFetchFile(value).apply(attributes.path).leftMap(mapper.to)

    override def saveFile[R](
        description: FileDescription,
        source: AkkaSource
    )(implicit mapper: Mapper[SaveFileRejection, R], as: ActorSystem, sc: Scheduler): IO[R, FileAttributes] =
      new RemoteDiskStorageSaveFile(this).apply(description, source).leftMap(mapper.to)

    override def moveFile[R](
        sourcePath: Uri.Path,
        description: FileDescription
    )(implicit mapper: Mapper[MoveFileRejection, R], as: ActorSystem, sc: Scheduler): IO[R, FileAttributes] =
      new RemoteDiskStorageMoveFile(this).apply(sourcePath, description).leftMap(mapper.to)
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

  val context: ContextValue = ContextValue(contexts.storage)

  implicit private val storageEncoder: Encoder[Storage] =
    Encoder.instance(s => s.storageValue.asJson.addContext(s.source.value.topContextValueOrEmpty.contextObj))

  implicit val storageJsonLdEncoder: JsonLdEncoder[Storage] = JsonLdEncoder.computeFromCirce(_.id, context)
}
