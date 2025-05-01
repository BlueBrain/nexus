package ch.epfl.bluebrain.nexus.delta.kernel.utils

import org.http4s.Uri

trait UrlUtils

/**
  * Provide helpers allowing to encode and decode uris following the RFC 3986 specification
  *
  * Relies on the implementation provided by the http4s library
  *
  * @see
  *   [[https://datatracker.ietf.org/doc/html/rfc3986]]
  */
object UrlUtils extends UrlUtils {

  /**
    * Percent-encodes a string for a full URI/URL
    */
  def encodeUri(url: String): String = Uri.encode(url)

  /**
    * Percent-encodes a string for a uri segment/path
    */
  def encodeUriPath(url: String): String = Uri.pathEncode(url)

  /**
    * Percent-encodes a string for a uri query
    */
  def encodeUriQuery(url: String): String = Uri.encode(url)

  def decodeUri(value: String): String = Uri.decode(value)

  def decodeUriPath(path: Uri.Path): String = Uri.decode(path.toString())
}
