package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model

import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.Storage.Metadata
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageValue.{DiskStorageValue, S3StorageValue}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.{contexts, Storages}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.OrderingFields
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import io.circe.syntax.*
import io.circe.{Encoder, Json, JsonObject}

sealed trait Storage extends Product with Serializable {

  /**
    * @return
    *   the storage id
    */
  def id: Iri

  /**
    * @return
    *   a reference to the project that the storage belongs to
    */
  def project: ProjectRef

  /**
    * @return
    *   the original json document provided at creation or update
    */
  def source: Json

  /**
    * @return
    *   ''true'' if this store is the project's default, ''false'' otherwise
    */
  def default: Boolean

  /**
    * @return
    *   the storage type
    */
  def tpe: StorageType = storageValue.tpe

  def storageValue: StorageValue

  /**
    * @return
    *   [[Storage]] metadata
    */
  def metadata: Metadata = Metadata(storageValue.algorithm)
}

object Storage {

  /**
    * A storage that stores and fetches files from a local volume
    */
  final case class DiskStorage(
      id: Iri,
      project: ProjectRef,
      value: DiskStorageValue,
      source: Json
  ) extends Storage {
    override val default: Boolean           = value.default
    override val storageValue: StorageValue = value
  }

  /**
    * A storage that stores and fetches files from an S3 compatible service
    */
  final case class S3Storage(
      id: Iri,
      project: ProjectRef,
      value: S3StorageValue,
      source: Json
  ) extends Storage {
    override val default: Boolean           = value.default
    override val storageValue: StorageValue = value
  }

  /**
    * Storage metadata.
    *
    * @param algorithm
    *   the digest algorithm, e.g. "SHA-256"
    */
  final case class Metadata(algorithm: DigestAlgorithm)

  implicit private[storages] val storageEncoder: Encoder.AsObject[Storage] =
    Encoder.encodeJsonObject.contramapObject { s =>
      s.storageValue.asJsonObject.add(keywords.tpe, s.tpe.types.asJson)
    }

  implicit val storageJsonLdEncoder: JsonLdEncoder[Storage] = JsonLdEncoder.computeFromCirce(_.id, Storages.context)

  implicit private val storageMetadataEncoder: Encoder.AsObject[Metadata] =
    Encoder.encodeJsonObject.contramapObject(meta => JsonObject("_algorithm" -> meta.algorithm.asJson))

  implicit val storageMetadataJsonLdEncoder: JsonLdEncoder[Metadata] =
    JsonLdEncoder.computeFromCirce(ContextValue(contexts.storagesMetadata))

  implicit val storageOrderingFields: OrderingFields[Storage] =
    OrderingFields { case "_algorithm" =>
      Ordering[String] on (_.storageValue.algorithm.value)
    }
}
