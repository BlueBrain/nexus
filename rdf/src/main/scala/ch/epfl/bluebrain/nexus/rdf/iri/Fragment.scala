package ch.epfl.bluebrain.nexus.rdf.iri

import cats.{Eq, Show}
import ch.epfl.bluebrain.nexus.rdf.PctString._
import ch.epfl.bluebrain.nexus.rdf.iri.IriParser._

/**
  * Fragment part of an Iri as defined by RFC 3987.
  *
  * @param value the string value of the fragment
  */
final case class Fragment private[iri] (value: String) {

  /**
    * @return the string representation for the Path segment compatible with the rfc3987
    *         (using percent-encoding only for delimiters)
    */
  lazy val iriString: String = value.pctEncodeIgnore(`ifragment_allowed`)

  /**
    * @return the string representation using percent-encoding for the Fragment segment
    *         when necessary according to rfc3986
    */
  lazy val uriString: String = value.pctEncodeIgnore(`fragment_allowed`)

}

object Fragment {

  /**
    * Attempts to parse the argument string as an `ifragment` as defined by RFC 3987.
    *
    * @param string the string to parse as a Fragment
    * @return Right(Fragment) if the parsing succeeds, Left(error) otherwise
    */
  final def apply(string: String): Either[String, Fragment] =
    new IriParser(string).parseFragment

  final implicit val fragmentShow: Show[Fragment] = Show.show(_.iriString)
  final implicit val fragmentEq: Eq[Fragment]     = Eq.fromUniversalEquals
}
