package ch.epfl.bluebrain.nexus.delta.plugins.archive.model

import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoder
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoderError.ParsingFailure
import io.circe.Encoder

/**
  * Enumeration of representations for resource references.
  */
sealed trait ArchiveResourceRepresentation extends Product with Serializable {

  /**
    * Default extension for the format
    */
  def extension: String

}

object ArchiveResourceRepresentation {

  /**
    * Source representation of a resource.
    */
  final case object SourceJson extends ArchiveResourceRepresentation {
    override def extension: String = ".json"

    override val toString: String = "source"
  }

  /**
    * Compacted JsonLD representation of a resource.
    */
  final case object CompactedJsonLd extends ArchiveResourceRepresentation {
    override def extension: String = ".json"

    override val toString: String = "compacted"
  }

  /**
    * Expanded JsonLD representation of a resource.
    */
  final case object ExpandedJsonLd extends ArchiveResourceRepresentation {
    override def extension: String = ".json"

    override val toString: String = "expanded"
  }

  /**
    * NTriples representation of a resource.
    */
  final case object NTriples extends ArchiveResourceRepresentation {
    override def extension: String = ".nt"

    override val toString: String = "n-triples"
  }

  final case object NQuads extends ArchiveResourceRepresentation {
    override def extension: String = ".nq"

    override val toString: String = "n-quads"
  }

  /**
    * Dot representation of a resource.
    */
  final case object Dot extends ArchiveResourceRepresentation {
    override def extension: String = ".dot"

    override val toString: String = "dot"
  }

  implicit final val archiveResourceRepresentationJsonLdDecoder: JsonLdDecoder[ArchiveResourceRepresentation] =
    JsonLdDecoder.stringJsonLdDecoder.andThen { (cursor, str) =>
      str match {
        case SourceJson.toString      => Right(SourceJson)
        case CompactedJsonLd.toString => Right(CompactedJsonLd)
        case ExpandedJsonLd.toString  => Right(ExpandedJsonLd)
        case NTriples.toString        => Right(NTriples)
        case NQuads.toString          => Right(NQuads)
        case Dot.toString             => Right(Dot)
        case other                    => Left(ParsingFailure("Format", other, cursor.history))
      }
    }

  implicit final val archiveResourceRepresentationEncoder: Encoder[ArchiveResourceRepresentation] =
    Encoder.encodeString.contramap {
      _.toString
    }
}
