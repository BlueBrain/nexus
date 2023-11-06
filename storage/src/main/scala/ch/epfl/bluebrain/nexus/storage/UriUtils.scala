package ch.epfl.bluebrain.nexus.storage

import akka.http.scaladsl.model.Uri

object UriUtils {

  /**
    * Adds a segment to the end of the Uri
    */
  def addPath(uri: Uri, segment: String): Uri = {
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
}
