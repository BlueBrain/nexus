package ch.epfl.bluebrain.nexus.rdf.iri

import cats.{Eq, Show}
import ch.epfl.bluebrain.nexus.rdf.PctString._
import ch.epfl.bluebrain.nexus.rdf.iri.IriParser._
import io.circe.{Decoder, Encoder}

/**
  * NID part of an Urn as defined by RFC 8141.
  *
  * @param value the string value of the fragment
  */
final case class Nid private[iri] (value: String) {

  /**
    * @return the string representation for the Nid segment compatible with the rfc3987
    *         (using percent-encoding only for delimiters)
    */
  lazy val iriString: String = value.pctEncodeIgnore(`inid_allowed`)

  /**
    * @return the string representation using percent-encoding for the Nid segment
    *         when necessary according to rfc3986
    */
  lazy val uriString: String = value.pctEncodeIgnore(`nid_allowed`)
}

object Nid {

  /**
    * Attempts to parse the argument string as a `NID` as defined by RFC 8141.
    *
    * @param string the string to parse as a NID
    * @return Right(NID) if the parsing succeeds, Left(error) otherwise
    */
  final def apply(string: String): Either[String, Nid] =
    new IriParser(string).parseNid

  implicit final val nidShow: Show[Nid]         = Show.show(_.iriString)
  implicit final val nidEq: Eq[Nid]             = Eq.fromUniversalEquals
  implicit final val queryEncoder: Encoder[Nid] = Encoder.encodeString.contramap(_.iriString)
  implicit final val queryDecoder: Decoder[Nid] = Decoder.decodeString.emap(Nid.apply)
}
