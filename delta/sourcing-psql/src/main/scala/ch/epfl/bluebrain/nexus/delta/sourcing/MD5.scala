package ch.epfl.bluebrain.nexus.delta.sourcing

import java.security.MessageDigest

object MD5 {

  private val MD5 = MessageDigest.getInstance("MD5")

  def hash(input: String): String =
    MD5.digest(input.getBytes).map("%02x".format(_)).mkString
}
