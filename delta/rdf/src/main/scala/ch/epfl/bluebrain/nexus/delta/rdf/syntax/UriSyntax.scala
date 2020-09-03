package ch.epfl.bluebrain.nexus.delta.rdf.syntax

import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.delta.rdf._

trait UriSyntax {
  implicit final def uriStringContextSyntax(sc: StringContext): UriStringContextOps = new UriStringContextOps(sc)
  implicit final def uriStringSyntax(string: String): UriStringOps                  = new UriStringOps(string)
}

final class UriStringContextOps(private val sc: StringContext) extends AnyVal {

  /**
    * Construct a Uri without checking the validity of the format.
    */
  def uri(args: Any*): Uri = Uri(sc.s(args: _*))
}

final class UriStringOps(private val string: String) extends AnyVal {

  /**
    * Attempts to construct an Uri, returning a Left when it does not have the correct Uri format.
    */
  def toUri: Either[String, Uri] = uri(string)
}
