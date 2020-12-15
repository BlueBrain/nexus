package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model

import akka.actor.ActorSystem
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.{FileAttributes, FileDescription}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.EncryptionState.Decrypted
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.Secret.DecryptedSecret
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageValue.{DiskStorageValue, RemoteDiskStorageValue, S3StorageValue}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.StorageFileRejection
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.disk.{DiskStorageFetchFile, DiskStorageSaveFile}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote.{RemoteDiskStorageFetchFile, RemoteDiskStorageSaveFile}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3.{S3StorageFetchFile, S3StorageSaveFile}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.{contexts, AkkaSource}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.Mapper
import ch.epfl.bluebrain.nexus.delta.sdk.model.Label
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import io.circe.syntax._
import io.circe.{Encoder, Json}
import monix.bio.IO

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
  def tags: Map[Label, Long]

  /**
    * @return the original json document provided at creation or update
    */
  def source: DecryptedSecret[Json]

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
  )(implicit mapper: Mapper[StorageFileRejection, R], as: ActorSystem): IO[R, AkkaSource]

  /**
    * Save a file using the current storage.
    *
    * @param description the file description metadata
    * @param source      the file content
    */
  def saveFile[R](
      description: FileDescription,
      source: AkkaSource
  )(implicit mapper: Mapper[StorageFileRejection, R], as: ActorSystem): IO[R, FileAttributes]

  private[model] def storageValue: StorageValue[Decrypted]

}

object Storage {

  /**
    * A storage that stores and fetches files from a local volume
    */
  final case class DiskStorage(
      id: Iri,
      project: ProjectRef,
      value: DiskStorageValue[Decrypted],
      tags: Map[Label, Long],
      source: DecryptedSecret[Json]
  ) extends Storage {
    override val default: Boolean                      = value.default
    override val storageValue: StorageValue[Decrypted] = value

    private val diskFetchFile = new DiskStorageFetchFile(id)

    override def fetchFile[R](
        attributes: FileAttributes
    )(implicit mapper: Mapper[StorageFileRejection, R], as: ActorSystem): IO[R, AkkaSource] =
      diskFetchFile(attributes.location.path).leftMap(mapper.to)

    override def saveFile[R](
        description: FileDescription,
        source: AkkaSource
    )(implicit mapper: Mapper[StorageFileRejection, R], as: ActorSystem): IO[R, FileAttributes] =
      new DiskStorageSaveFile(this).apply(description, source).leftMap(mapper.to)
  }

  /**
    * A storage that stores and fetches files from an S3 compatible service
    */
  final case class S3Storage(
      id: Iri,
      project: ProjectRef,
      value: S3StorageValue[Decrypted],
      tags: Map[Label, Long],
      source: DecryptedSecret[Json]
  ) extends Storage {

    override val default: Boolean                      = value.default
    override val storageValue: StorageValue[Decrypted] = value

    override def fetchFile[R](
        attributes: FileAttributes
    )(implicit mapper: Mapper[StorageFileRejection, R], as: ActorSystem): IO[R, AkkaSource] =
      new S3StorageFetchFile(id, value).apply(attributes.path).leftMap(mapper.to)

    override def saveFile[R](
        description: FileDescription,
        source: AkkaSource
    )(implicit mapper: Mapper[StorageFileRejection, R], as: ActorSystem): IO[R, FileAttributes] =
      new S3StorageSaveFile(this).apply(description, source).leftMap(mapper.to)

  }

  /**
    * A storage that stores and fetches files from a remote volume using a well-defined API
    */
  final case class RemoteDiskStorage(
      id: Iri,
      project: ProjectRef,
      value: RemoteDiskStorageValue[Decrypted],
      tags: Map[Label, Long],
      source: DecryptedSecret[Json]
  ) extends Storage {
    override val default: Boolean                      = value.default
    override val storageValue: StorageValue[Decrypted] = value

    override def fetchFile[R](
        attributes: FileAttributes
    )(implicit mapper: Mapper[StorageFileRejection, R], as: ActorSystem): IO[R, AkkaSource] =
      RemoteDiskStorageFetchFile(attributes.path).leftMap(mapper.to)

    override def saveFile[R](
        description: FileDescription,
        source: AkkaSource
    )(implicit mapper: Mapper[StorageFileRejection, R], as: ActorSystem): IO[R, FileAttributes] =
      RemoteDiskStorageSaveFile(description, source).leftMap(mapper.to)

  }

  val context: ContextValue = ContextValue(contexts.storage)

  implicit private val storageEncoder: Encoder[Storage] =
    Encoder.instance(s => s.storageValue.asJson.addContext(s.source.value.topContextValueOrEmpty.contextObj))

  implicit val storageJsonLdEncoder: JsonLdEncoder[Storage] = JsonLdEncoder.computeFromCirce(_.id, context)
}
