package ch.epfl.bluebrain.nexus.delta.plugins.archive.model

/**
  * Enumeration of archive reference types.
  */
sealed trait ArchiveReferenceType extends Product with Serializable

object ArchiveReferenceType {

  /**
    * A resource reference.
    */
  final case object Resource extends ArchiveReferenceType

  /**
    * A file reference.
    */
  final case object File extends ArchiveReferenceType

  /**
    * An archive file reference, but a link rather than a full reference
    */
  final case object FileLink extends ArchiveReferenceType
}
