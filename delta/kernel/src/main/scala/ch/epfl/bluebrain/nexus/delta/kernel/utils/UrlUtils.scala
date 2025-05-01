package ch.epfl.bluebrain.nexus.delta.kernel.utils

import org.http4s.Uri

trait UrlUtils

object UrlUtils extends UrlUtils {

  def encodeUri(url: String): String = Uri.encode(url)

  /**
    * Encodes the passed ''url''.
    */
  def encodeUriPath(url: String): String = Uri.pathEncode(url)

  def encodeUriQuery(url: String): String = Uri.encode(url)

  def decodeUri(value: String): String = Uri.decode(value)

  def decodeUriPath(path: Uri.Path): String = Uri.decode(path.toString())
}
