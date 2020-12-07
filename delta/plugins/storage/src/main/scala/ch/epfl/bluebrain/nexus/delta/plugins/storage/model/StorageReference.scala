package ch.epfl.bluebrain.nexus.delta.plugins.storage.model

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri

/**
  * Enumeration of storage reference types
  */
sealed trait StorageReference {

  /**
    * @return the storage @id value
    */
  def id: Iri

  /**
    * @return the storage revision
    */
  def rev: Long
}

object StorageReference {

  /**
    * Disk storage reference
    */
  final case class DiskStorageReference(id: Iri, rev: Long) extends StorageReference

  /**
    * S3 Disk storage reference
    */
  final case class S3StorageReference(id: Iri, rev: Long) extends StorageReference

  /**
    * Remote Disk storage reference
    */
  final case class RemoteDiskStorageReference(id: Iri, rev: Long) extends StorageReference

}
