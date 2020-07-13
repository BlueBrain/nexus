package ch.epfl.bluebrain.nexus.kg.resources

import ch.epfl.bluebrain.nexus.rdf.Iri.AbsoluteIri

/**
  * Enumeration of storage reference types
  */
sealed trait StorageReference {

  /**
    *
    * @return the storage @id value
    */
  def id: AbsoluteIri

  /**
    * @return the storage revision
    */
  def rev: Long
}

object StorageReference {

  /**
    * Disk storage reference
    */
  final case class DiskStorageReference(id: AbsoluteIri, rev: Long) extends StorageReference

  /**
    * Remote Disk storage reference
    */
  final case class RemoteDiskStorageReference(id: AbsoluteIri, rev: Long) extends StorageReference

  /**
    * S3 Disk storage reference
    */
  final case class S3StorageReference(id: AbsoluteIri, rev: Long) extends StorageReference

}
