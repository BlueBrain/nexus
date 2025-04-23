package ch.epfl.bluebrain.nexus.delta.kernel.utils

import org.http4s.Uri

import java.net.{URLDecoder, URLEncoder}
import java.nio.charset.StandardCharsets

trait UrlUtils

object UrlUtils extends UrlUtils {

  /**
    * Encodes the passed ''url''.
    */
  def encode(url: String): String =
    URLEncoder.encode(url, StandardCharsets.UTF_8.name()).replace("+", "%20")

  def decode(url: String): String =
    URLDecoder.decode(url, StandardCharsets.UTF_8.name())

  def decode(uri: Uri): String =
    URLDecoder.decode(uri.toString(), StandardCharsets.UTF_8.name())

  def decode(path: Uri.Path): String =
    URLDecoder.decode(path.toString(), StandardCharsets.UTF_8.name())
}
