package ch.epfl.bluebrain.nexus.rdf.iri

import cats.{Eq, Show}

/**
  * Scheme part of an Iri as defined by RFC 3987.
  *
  * @param value the string value of the scheme
  */
final case class Scheme private[iri] (value: String) {

  /**
    * Whether this scheme identifies an URN.
    */
  def isUrn: Boolean = value equalsIgnoreCase "urn"

  /**
    * Whether this scheme identifies an HTTPS Iri.
    */
  def isHttps: Boolean = value equalsIgnoreCase "https"

  /**
    * Whether this scheme identifies an HTTP Iri.
    */
  def isHttp: Boolean = value equalsIgnoreCase "http"
}

object Scheme {

  /**
    * Attempts to construct a scheme from the argument string value.  The provided value, if correct, will be normalized
    * such that:
    * {{{
    *   Scheme("HTTPS").right.get.value == "https"
    * }}}
    *
    * @param value the string representation of the scheme
    * @return Right(value) if successful or Left(error) if the string does not conform to the RFC 3987 format
    */
  final def apply(value: String): Either[String, Scheme] =
    new IriParser(value).parseScheme

  final implicit val schemeShow: Show[Scheme] = Show.show(_.value)
  final implicit val schemeEq: Eq[Scheme]     = Eq.fromUniversalEquals
}
