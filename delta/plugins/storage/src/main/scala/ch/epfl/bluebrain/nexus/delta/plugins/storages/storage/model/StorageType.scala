package ch.epfl.bluebrain.nexus.delta.plugins.storages.storage.model

import ch.epfl.bluebrain.nexus.delta.plugins.storages.storage.nxvStorage
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv

/**
  * Enumeration of Storage types.
  */
sealed trait StorageType extends Product with Serializable {

  /**
    * @return the type id
    */
  def iri: Iri

  /**
    * The JSON-LD types for the given storage type
    */
  def types: Set[Iri] = Set(nxvStorage, iri)
}

object StorageType {

  /**
    * A local disk storage type.
    */
  final case object DiskStorage extends StorageType {
    override val toString: String = "DiskStorage"
    override val iri: Iri         = nxv + toString
  }

  /**
    * An S3 compatible storage type.
    */
  final case object S3Storage extends StorageType {
    override val toString: String = "S3Storage"
    override val iri: Iri         = nxv + toString
  }

  /**
    * A remote disk storage type.
    */
  final case object RemoteDiskStorage extends StorageType {
    override val toString: String = "RemoteDiskStorage"
    override val iri: Iri         = nxv + toString
  }
}
