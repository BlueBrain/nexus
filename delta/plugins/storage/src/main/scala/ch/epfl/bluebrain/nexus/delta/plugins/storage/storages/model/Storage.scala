package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model

import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.contexts
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.EncryptionState.Decrypted
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageValue.{DiskStorageValue, RemoteDiskStorageValue, S3StorageValue}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.model.Label
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import io.circe.{Encoder, Json}
import io.circe.syntax._

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
  def source: Json

  /**
    * @return ''true'' if this store is the project's default, ''false'' otherwise
    */
  def default: Boolean

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
      source: Json
  ) extends Storage {
    override val default: Boolean                      = value.default
    override val storageValue: StorageValue[Decrypted] = value
  }

  /**
    * A storage that stores and fetches files from an S3 compatible service
    */
  final case class S3Storage(
      id: Iri,
      project: ProjectRef,
      value: S3StorageValue[Decrypted],
      tags: Map[Label, Long],
      source: Json
  ) extends Storage {
    private[storage] def address(bucket: String): Uri  =
      value.endpoint match {
        case Some(host) if host.scheme.trim.isEmpty => Uri(s"https://$bucket.$host")
        case Some(e)                                => e.withHost(s"$bucket.${e.authority.host}")
        case None                                   => value.region.fold(s"https://$bucket.s3.amazonaws.com")(r => s"https://$bucket.s3.$r.amazonaws.com")
      }
    override val default: Boolean                      = value.default
    override val storageValue: StorageValue[Decrypted] = value
  }

  /**
    * A storage that stores and fetches files from a remote volume using a well-defined API
    */
  final case class RemoteDiskStorage(
      id: Iri,
      project: ProjectRef,
      value: RemoteDiskStorageValue[Decrypted],
      tags: Map[Label, Long],
      source: Json
  ) extends Storage {
    override val default: Boolean                      = value.default
    override val storageValue: StorageValue[Decrypted] = value
  }

  val context: ContextValue = ContextValue(contexts.storage)

  implicit private val storageEncoder: Encoder[Storage] =
    Encoder.instance(s => s.storageValue.asJson.addContext(s.source.topContextValueOrEmpty.contextObj))

  implicit val storageJsonLdEncoder: JsonLdEncoder[Storage] = JsonLdEncoder.computeFromCirce(_.id, context)
}
