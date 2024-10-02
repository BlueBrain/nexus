package ch.epfl.bluebrain.nexus.delta.kernel

import java.security.MessageDigest

/**
  * A class providing an MD5 digest.
  *
  * Note that the java MessageDigest is *not* thread safe; the same input can generate different hashes when making
  * concurrent calls to the same instance
  */
class MD5 {
  val digest: MessageDigest = MessageDigest.getInstance("MD5")
}

object MD5 {

  /**
    * Generate a String MD5 hash of the input
    * @param input
    *   The String to hash
    */
  def hash(input: String): String =
    new MD5().digest.digest(input.getBytes).map("%02x".format(_)).mkString
}
