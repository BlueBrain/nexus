package ch.epfl.bluebrain.nexus.rdf.iri

import cats.implicits._
import cats.{Eq, Show}
import ch.epfl.bluebrain.nexus.rdf.iri.Curie._
import ch.epfl.bluebrain.nexus.rdf.iri.Iri._
import io.circe.{Decoder, Encoder}

/**
  * A Compact URI as defined in the Json-LD 1.1 spec.
  * A curie is form by a ''prefix'', a '':'' and a ''reference''.
  * Example: xsd:integer
  *
  * @param prefix    the curie prefix
  * @param reference the curie reference
  */
final case class Curie(prefix: Prefix, reference: String) {

  /**
    * Converts the curie to an iri using the provided ''namespace'' and the curie ''reference''.
    *
    * @param namespace the namespace to produce the resulting iri
    * @return an [[Uri]] when successfully joined the ''namespace'' and ''reference'' or
    *         an string with the error message otherwise
    */
  def toIri(namespace: Uri): Either[String, Uri] =
    Iri.uri(namespace.iriString + reference)

  /**
    * Converts the curie to an iri using the provided ''prefixMappings'' to resolve the value of the ''prefix''.
    *
    * @param prefixMappings the mappings to attempt to apply to the ''prefix'' value of the curie
    * @return an [[Uri]] when successfully joined the resolution of the prefix with the ''reference'' or
    *         an string with the error message otherwise
    */
  def toIri(prefixMappings: Map[Prefix, Uri]): Either[String, Option[Uri]] =
    prefixMappings.get(prefix).map(toIri).sequence
}

object Curie {

  /**
    * Attempt to construct a new [[Curie]] from the argument validating the structure and the character encodings as per
    * ''CURIE Syntax 1.0''.
    *
    * @param string the string to parse as a Curie.
    * @return Right(Curie) if the string conforms to specification, Left(error) otherwise
    */
  final def apply(string: String): Either[String, Curie] =
    new IriParser(string).parseCurie

  /**
    * The Compact URI prefix as defined by W3C in ''CURIE Syntax 1.0''.
    *
    * @param value the prefix value
    */
  final case class Prefix private[iri] (value: String)

  object Prefix {

    val idAlphaNum = """^@[a-zA-Z0-9]+$""".r.pattern

    def isReserved(term: String): Boolean =
      idAlphaNum.matcher(term).matches()

    /**
      * Attempt to construct a new [[Prefix]] from the argument validating the structure and the character encodings as per
      * ''CURIE Syntax 1.0''.
      *
      * @param string the string to parse as a Prefix.
      * @return Right(prefix) if the string conforms to specification, Left(error) otherwise
      */
    final def apply(string: String): Either[String, Prefix] =
      new IriParser(string).parsePrefix

    implicit final val prefixShow: Show[Prefix]       = Show.show(_.value)
    implicit final val prefixEq: Eq[Prefix]           = Eq.fromUniversalEquals
    implicit final val prefixEncoder: Encoder[Prefix] = Encoder.encodeString.contramap(_.value)
    implicit final val prefixDecoder: Decoder[Prefix] = Decoder.decodeString.emap(Prefix.apply)
  }

  implicit final def curieShow(implicit p: Show[Prefix]): Show[Curie] =
    Show.show { case Curie(prefix, reference) => prefix.show + ":" + reference }

  implicit final val curieEq: Eq[Curie] = Eq.fromUniversalEquals

  implicit final val curieEncoder: Encoder[Curie] = Encoder.encodeString.contramap(_.show)
  implicit final val curieDecoder: Decoder[Curie] = Decoder.decodeString.emap(Curie.apply)
}
