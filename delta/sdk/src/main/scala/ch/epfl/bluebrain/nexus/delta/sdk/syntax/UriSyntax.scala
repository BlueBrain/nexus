package ch.epfl.bluebrain.nexus.delta.sdk.syntax

import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.model.Uri.Path
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.model.Label
import ch.epfl.bluebrain.nexus.delta.sdk.syntax.UriSyntax.uri

import scala.util.Try

trait UriSyntax {
  implicit final def uriStringContextSyntax(sc: StringContext): UriStringContextOps = new UriStringContextOps(sc)
  implicit final def uriStringSyntax(string: String): UriStringOps                  = new UriStringOps(string)
  implicit final def uriSyntax(uri: Uri): UriOps                                    = new UriOps(uri)
}

object UriSyntax {
  private[sdk] def uri(string: String): Either[String, Uri] =
    Try(Uri(string)).toEither.leftMap(_ => s"'$string' is not an Uri")
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

final class UriOps(private val uri: Uri) extends AnyVal {

  /**
    * Constructs an [[Iri]] from a [[Uri]]
    */
  def toIri: Iri = Iri.unsafe(uri.toString)

  /**
    * Adds a segment to the end of the Uri
    */
  def /(segment: Label): Uri = /(segment.value)

  /**
    * Add a final slash to the uri
    */
  def finalSlash(): Uri =
    if (uri.path.endsWithSlash)
      uri
    else
      uri.copy(path = uri.path ++ Path./)

  /**
    * Adds a segment to the end of the Uri
    */
  def /(segment: String): Uri = {
    lazy val segmentStartsWithSlash = segment.startsWith("/")
    lazy val uriEndsWithSlash       = uri.path.endsWithSlash
    if (uriEndsWithSlash && segmentStartsWithSlash)
      uri.copy(path = uri.path + segment.drop(1))
    else if (uriEndsWithSlash)
      uri.copy(path = uri.path + segment)
    else if (segmentStartsWithSlash)
      uri.copy(path = uri.path / segment.drop(1))
    else
      uri.copy(path = uri.path / segment)
  }

  /**
    * Adds a path to the end of the current Uris' path
    */
  def +(path: Uri.Path): Uri =
    uri.copy(path = uri.path ++ path)
}
