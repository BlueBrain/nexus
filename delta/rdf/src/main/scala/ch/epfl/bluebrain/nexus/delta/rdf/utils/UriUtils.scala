package ch.epfl.bluebrain.nexus.delta.rdf.utils

import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.model.Uri.Path
import cats.syntax.all._

import scala.util.Try

object UriUtils {

  /**
    * Construct a Uri safely.
    */
  def uri(string: String): Either[String, Uri] =
    Try(Uri(string)).toEither.leftMap(_ => s"'$string' is not an Uri")

  /**
    * Adds a segment to the end of the Uri
    */
  def /(uri: Uri, segment: String): Uri = {
    if (segment.trim.isEmpty) uri
    else {
      val segmentStartsWithSlash = segment.startsWith("/")
      val uriEndsWithSlash       = uri.path.endsWithSlash
      if (uriEndsWithSlash && segmentStartsWithSlash)
        uri.copy(path = uri.path + segment.drop(1))
      else if (uriEndsWithSlash)
        uri.copy(path = uri.path + segment)
      else if (segmentStartsWithSlash)
        uri.copy(path = uri.path / segment.drop(1))
      else
        uri.copy(path = uri.path / segment)
    }
  }

  /**
    * Add a final slash to the uri
    */
  def finalSlash(uri: Uri): Uri =
    if (uri.path.endsWithSlash)
      uri
    else
      uri.copy(path = uri.path ++ Path./)
}
