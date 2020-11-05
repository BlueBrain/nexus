package ch.epfl.bluebrain.nexus.delta.kernel.utils

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

trait UrlUtils {}

object UrlUtils extends UrlUtils {

  /**
    * Encodes the passed ''url''.
    */
  def encode(url: String): String =
    URLEncoder.encode(url, StandardCharsets.UTF_8.name()).replace("+", "%20")
}
