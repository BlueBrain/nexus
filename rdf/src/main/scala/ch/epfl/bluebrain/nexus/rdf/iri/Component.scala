package ch.epfl.bluebrain.nexus.rdf.iri

import cats.{Eq, Show}
import ch.epfl.bluebrain.nexus.rdf.PctString._
import ch.epfl.bluebrain.nexus.rdf.iri.IriParser._
import io.circe.{Decoder, Encoder}

/**
  * Urn R or Q component as defined by RFC 8141.
  */
final case class Component private[iri] (value: String) {

  /**
    * @return the string representation for the Component segment compatible with the rfc3987
    *         (using percent-encoding only for delimiters)
    */
  lazy val iriString: String = value.pctEncodeIgnore(`icomponent_allowed`)

  /**
    * @return the string representation using percent-encoding for the Component segment
    *         when necessary according to rfc3986
    */
  lazy val uriString: String = value.pctEncodeIgnore(`component_allowed`)
}

object Component {

  /**
    * Attempts to parse the argument string as a Urn R or Q component as defined by RFC 8141, but with the character
    * restrictions of RFC 3897.
    *
    * @param string the string to parse as a URN component
    * @return Right(Component) if the parsing succeeds, Left(error) otherwise
    */
  final def apply(string: String): Either[String, Component] =
    new IriParser(string).parseComponent

  implicit final val componentShow: Show[Component]       = Show.show(_.iriString)
  implicit final val componentEq: Eq[Component]           = Eq.fromUniversalEquals
  implicit final val componentEncoder: Encoder[Component] = Encoder.encodeString.contramap(_.iriString)
  implicit final val componentDecoder: Decoder[Component] = Decoder.decodeString.emap(Component.apply)
}
