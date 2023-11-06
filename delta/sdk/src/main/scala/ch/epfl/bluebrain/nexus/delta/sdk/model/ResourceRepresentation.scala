package ch.epfl.bluebrain.nexus.delta.sdk.model

import cats.Order
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoder
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoderError.ParsingFailure
import io.circe.{Decoder, Encoder}

/**
  * Enumeration of representations for resources.
  */
sealed trait ResourceRepresentation extends Product with Serializable {

  /**
    * Default extension for the format
    */
  def extension: String

}

object ResourceRepresentation {

  /**
    * Source representation of a resource.
    */
  final case object SourceJson extends ResourceRepresentation {
    override def extension: String = ".json"

    override val toString: String = "source"
  }

  /**
    * Source representation of a resource.
    */
  final case object AnnotatedSourceJson extends ResourceRepresentation {
    override def extension: String = ".json"

    override val toString: String = "annotated-source"
  }

  /**
    * Compacted JsonLD representation of a resource.
    */
  final case object CompactedJsonLd extends ResourceRepresentation {
    override def extension: String = ".json"

    override val toString: String = "compacted"
  }

  /**
    * Expanded JsonLD representation of a resource.
    */
  final case object ExpandedJsonLd extends ResourceRepresentation {
    override def extension: String = ".json"

    override val toString: String = "expanded"
  }

  /**
    * NTriples representation of a resource.
    */
  final case object NTriples extends ResourceRepresentation {
    override def extension: String = ".nt"

    override val toString: String = "n-triples"
  }

  final case object NQuads extends ResourceRepresentation {
    override def extension: String = ".nq"

    override val toString: String = "n-quads"
  }

  /**
    * Dot representation of a resource.
    */
  final case object Dot extends ResourceRepresentation {
    override def extension: String = ".dot"

    override val toString: String = "dot"
  }

  private def parse(value: String) =
    value match {
      case SourceJson.toString          => Right(SourceJson)
      case AnnotatedSourceJson.toString => Right(AnnotatedSourceJson)
      case CompactedJsonLd.toString     => Right(CompactedJsonLd)
      case ExpandedJsonLd.toString      => Right(ExpandedJsonLd)
      case NTriples.toString            => Right(NTriples)
      case NQuads.toString              => Right(NQuads)
      case Dot.toString                 => Right(Dot)
      case other                        => Left(s"$other is not a valid representation")
    }

  implicit final val resourceRepresentationJsonLdDecoder: JsonLdDecoder[ResourceRepresentation] =
    JsonLdDecoder.stringJsonLdDecoder.andThen { (cursor, str) =>
      parse(str).leftMap(_ => ParsingFailure("Format", str, cursor.history))
    }

  implicit final val resourceRepresentationDecoder: Decoder[ResourceRepresentation] =
    Decoder.decodeString.emap(parse)

  implicit final val resourceRepresentationEncoder: Encoder[ResourceRepresentation] =
    Encoder.encodeString.contramap {
      _.toString
    }

  implicit val resourceRepresentationOrder: Order[ResourceRepresentation] = Order.by(_.toString)
}
