package ch.epfl.bluebrain.nexus.delta.plugins.archive.model

import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoder
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoderError.ParsingFailure
import io.circe.Encoder

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

  implicit final val archiveResourceRepresentationJsonLdDecoder: JsonLdDecoder[ArchiveResourceRepresentation] =
    JsonLdDecoder.stringJsonLdDecoder.andThen { (cursor, str) =>
      str match {
        case "source"    => Right(SourceJson)
        case "compacted" => Right(CompactedJsonLd)
        case "expanded"  => Right(ExpandedJsonLd)
        case "n-triples" => Right(NTriples)
        case "dot"       => Right(Dot)
        case other       => Left(ParsingFailure("Format", other, cursor.history))
      }
    }

  implicit final val archiveResourceRepresentationEncoder: Encoder[ArchiveResourceRepresentation] =
    Encoder.encodeString.contramap {
      case SourceJson      => "source"
      case CompactedJsonLd => "compacted"
      case ExpandedJsonLd  => "expanded"
      case NTriples        => "n-triples"
      case Dot             => "dot"
    }
}
