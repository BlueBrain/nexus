package ch.epfl.bluebrain.nexus.delta.rdf.syntax

import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.utils.UriUtils

trait UriSyntax {
  implicit final def uriStringContextSyntax(sc: StringContext): UriStringContextOps = new UriStringContextOps(sc)
  implicit final def uriStringSyntax(string: String): UriStringOps                  = new UriStringOps(string)
  implicit final def uriSyntax(uri: Uri): UriOps                                    = new UriOps(uri)
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
  def toUri: Either[String, Uri] = UriUtils.uri(string)
}

final class UriOps(private val uri: Uri) extends AnyVal {

  /**
    * Constructs an [[Iri]] from a [[Uri]]
    */
  def toIri: Iri = Iri.unsafe(uri.toString)

  /**
    * Add a final slash to the uri
    */
  def finalSlash(): Uri = UriUtils.finalSlash(uri)

  /**
    * Adds a segment to the end of the Uri
    */
  def /(segment: String): Uri = UriUtils./(uri, segment)

  /**
    * Adds a path to the end of the current Uris' path
    */
  def +(path: Uri.Path): Uri = uri.copy(path = uri.path ++ path)
}
