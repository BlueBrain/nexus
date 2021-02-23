package ch.epfl.bluebrain.nexus.delta.plugins.archive.model

/**
  * Enumeration of representations for resource references.
  */
sealed trait ArchiveResourceRepresentation extends Product with Serializable

object ArchiveResourceRepresentation {

  /**
    * Source representation of a resource.
    */
  final case object SourceJson extends ArchiveResourceRepresentation

  /**
    * Compacted JsonLD representation of a resource.
    */
  final case object CompactedJsonLd extends ArchiveResourceRepresentation

  /**
    * Expanded JsonLD representation of a resource.
    */
  final case object ExpandedJsonLd extends ArchiveResourceRepresentation

  /**
    * NTriples representation of a resource.
    */
  final case object NTriples extends ArchiveResourceRepresentation

  /**
    * Dot representation of a resource.
    */
  final case object Dot extends ArchiveResourceRepresentation
}
