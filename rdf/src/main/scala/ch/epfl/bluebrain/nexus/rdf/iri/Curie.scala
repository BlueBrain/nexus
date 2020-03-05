package ch.epfl.bluebrain.nexus.rdf.iri

import cats.syntax.show._
import cats.{Eq, Show}
import ch.epfl.bluebrain.nexus.rdf.iri.Curie._
import ch.epfl.bluebrain.nexus.rdf.iri.Iri._

/**
  * A Compact URI as defined by W3C in ''CURIE Syntax 1.0''.
  * A curie is form by a ''prefix'', a '':'' and a ''reference''.
  * Example: xsd:integer
  *
  * @param prefix    the curie prefix
  * @param reference the curie reference
  */
final case class Curie(prefix: Prefix, reference: RelativeIri) {

  /**
    * Converts the curie to an iri using the provided ''namespace'' and the curie ''reference''.
    *
    * @param namespace the namespace to produce the resulting iri
    * @return an [[Uri]] when successfully joined the ''namespace'' and ''reference'' or
    *         an string with the error message otherwise
    */
  def toIri(namespace: Uri): Either[String, Uri] =
    Iri.uri(namespace.iriString + reference.iriString)

  /**
    * Converts the curie to an iri using the provided ''prefixMappings'' to resolve the value of the ''prefix''.
    *
    * @param prefixMappings the mappings to attempt to apply to the ''prefix'' value of the curie
    * @return an [[Uri]] when successfully joined the resolution of the prefix with the ''reference'' or
    *         an string with the error message otherwise
    */
  def toIri(prefixMappings: Map[Prefix, Uri]): Either[String, Uri] =
    prefixMappings
      .get(prefix)
      .toRight(s"Could not find a namespace definition for '${prefix.value}'")
      .flatMap(toIri)
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

    /**
      * Attempt to construct a new [[Prefix]] from the argument validating the structure and the character encodings as per
      * ''CURIE Syntax 1.0''.
      *
      * @param string the string to parse as a Prefix.
      * @return Right(prefix) if the string conforms to specification, Left(error) otherwise
      */
    final def apply(string: String): Either[String, Prefix] =
      new IriParser(string).parseNcName

    final implicit val prefixShow: Show[Prefix] = Show.show(_.value)
    final implicit val prefixEq: Eq[Prefix]     = Eq.fromUniversalEquals
  }

  final implicit def curieShow(implicit p: Show[Prefix], r: Show[RelativeIri]): Show[Curie] =
    Show.show { case Curie(prefix, reference) => prefix.show + ":" + reference.show }

  final implicit val curieEq: Eq[Curie] = Eq.fromUniversalEquals
}
