package ch.epfl.bluebrain.nexus.delta.plugins.storage.model

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri

/**
  * Enumeration of storage reference types
  */
sealed trait StorageRef {

  /**
    * @return the storage @id value
    */
  def id: Iri

  /**
    * @return the storage revision
    */
  def rev: Long
}

object StorageRef {

  /**
    * Disk storage reference
    */
  final case class DiskStorageRef(id: Iri, rev: Long) extends StorageRef

  /**
    * S3 Disk storage reference
    */
  final case class S3StorageRef(id: Iri, rev: Long) extends StorageRef

  /**
    * Remote Disk storage reference
    */
  final case class RemoteDiskStorageRef(id: Iri, rev: Long) extends StorageRef

}
