package ch.epfl.bluebrain.nexus.delta.plugins.storage.model

import ch.epfl.bluebrain.nexus.delta.plugins.storage.StoragesConfig.StorageTypeConfig
import ch.epfl.bluebrain.nexus.delta.plugins.storage.model.Storage.{DiskStorage, RemoteDiskStorage, S3Storage}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.model.StorageValue.{DiskStorageValue, RemoteDiskStorageValue, S3StorageValue}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.{schemas, StorageResource}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.Lens
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceRef.Latest
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{ApiMappings, ProjectBase, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Label, ResourceF, ResourceRef, ResourceUris}
import io.circe.Json

import java.time.Instant

/**
  * Enumeration of Storage state types
  */
sealed trait StorageState extends Product with Serializable {

  /**
    * @return the schema reference that storages conforms to
    */
  final def schema: ResourceRef = Latest(schemas.storage)

  /**
    * Converts the state into a resource representation.
    */
  def toResource(mappings: ApiMappings, base: ProjectBase)(implicit cfg: StorageTypeConfig): Option[StorageResource]

  /**
    * @return the current state revision
    */
  def rev: Long

  /**
    * @return the state deprecation status
    */
  def deprecated: Boolean

}

object StorageState {

  /**
    * Initial storage state.
    */
  final case object Initial extends StorageState {
    override def toResource(mappings: ApiMappings, base: ProjectBase)(implicit
        cfg: StorageTypeConfig
    ): Option[StorageResource] = None

    override def rev: Long = 0L

    override def deprecated: Boolean = false
  }

  /**
    * State for an existing storage
    *
    * @param id                the id of the storage
    * @param project           the project it belongs to
    * @param value             additional fields to configure the storage
    * @param source            the representation of the storage as posted by the subject
    * @param tags              the collection of tag aliases
    * @param rev               the current state revision
    * @param deprecated        the current state deprecation status
    * @param createdAt         the instant when the resource was created
    * @param createdBy         the subject that created the resource
    * @param updatedAt         the instant when the resource was last updated
    * @param updatedBy         the subject that last updated the resource
    */
  final case class Current(
      id: Iri,
      project: ProjectRef,
      value: StorageValue,
      source: Json,
      tags: Map[Label, Long],
      rev: Long,
      deprecated: Boolean,
      createdAt: Instant,
      createdBy: Subject,
      updatedAt: Instant,
      updatedBy: Subject
  ) extends StorageState {

    def storage(cfg: StorageTypeConfig): Storage =
      value match {
        case DiskStorageValue(default, volume, read, write, maxSize)                                       =>
          DiskStorage(
            id = id,
            project = project,
            default = default,
            algorithm = cfg.disk.digestAlgorithm,
            volume = volume,
            readPermission = read.getOrElse(cfg.disk.readPermission),
            writePermission = write.getOrElse(cfg.disk.writePermission),
            maxFileSize = maxSize.getOrElse(cfg.disk.maxFileSize),
            tags = tags,
            source = source
          )
        case S3StorageValue(default, bucket, endpoint, accessKey, secretKey, region, read, write, maxSize) =>
          S3Storage(
            id = id,
            project = project,
            default = default,
            algorithm = cfg.amazonUnsafe.digestAlgorithm,
            bucket = bucket,
            endpoint = endpoint.orElse(cfg.amazonUnsafe.defaultEndpoint),
            accessKey = accessKey.orElse(if (endpoint.forall(endpoint.contains)) cfg.amazonUnsafe.accessKey else None),
            secretKey = secretKey.orElse(if (endpoint.forall(endpoint.contains)) cfg.amazonUnsafe.secretKey else None),
            region = region,
            readPermission = read.getOrElse(cfg.amazonUnsafe.readPermission),
            writePermission = write.getOrElse(cfg.amazonUnsafe.writePermission),
            maxFileSize = maxSize.getOrElse(cfg.amazonUnsafe.maxFileSize),
            tags = tags,
            source = source
          )
        case RemoteDiskStorageValue(default, endpoint, cred, folder, read, write, maxSize)                 =>
          RemoteDiskStorage(
            id = id,
            project = project,
            default,
            algorithm = cfg.remoteDiskUnsafe.digestAlgorithm,
            endpoint = endpoint.getOrElse(cfg.remoteDiskUnsafe.endpoint),
            credentials = cred.orElse {
              if (endpoint.forall(_ == cfg.remoteDiskUnsafe.endpoint)) cfg.remoteDiskUnsafe.defaultCredentials else None
            },
            folder = folder,
            readPermission = read.getOrElse(cfg.remoteDiskUnsafe.readPermission),
            writePermission = write.getOrElse(cfg.remoteDiskUnsafe.writePermission),
            maxFileSize = maxSize.getOrElse(cfg.remoteDiskUnsafe.maxFileSize),
            tags = tags,
            source = source
          )
      }

    override def toResource(mappings: ApiMappings, base: ProjectBase)(implicit
        cfg: StorageTypeConfig
    ): Option[StorageResource] =
      Some(
        ResourceF(
          id = id,
          uris = ResourceUris("storages", project, id)(mappings, base),
          rev = rev,
          types = value.tpe.types,
          deprecated = deprecated,
          createdAt = createdAt,
          createdBy = createdBy,
          updatedAt = updatedAt,
          updatedBy = updatedBy,
          schema = schema,
          value = storage(cfg)
        )
      )
  }

  implicit val revisionLens: Lens[StorageState, Long] = (s: StorageState) => s.rev

}
