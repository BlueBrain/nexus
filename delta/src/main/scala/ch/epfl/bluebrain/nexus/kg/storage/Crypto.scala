package ch.epfl.bluebrain.nexus.kg.storage

import java.nio.charset.StandardCharsets.UTF_8
import java.util.Base64

import javax.crypto.Cipher._
import javax.crypto.spec.{PBEKeySpec, SecretKeySpec}
import javax.crypto.{Cipher, SecretKey, SecretKeyFactory}

object Crypto {

  private def getCipher = Cipher.getInstance("AES")

  /**
    * Encrypts the given input with the provided AES secret key.
    *
    * @note Fails and throws many kinds of exception for unsupported key formats.
    * @return the encrypted string in base64 encoding
    */
  def encrypt(key: SecretKey, input: String): String = {
    val cipher = getCipher
    cipher.init(ENCRYPT_MODE, key)
    val bytes  = cipher.doFinal(input.getBytes(UTF_8))
    Base64.getEncoder.encodeToString(bytes)
  }

  /**
    * Decrypts the given base64 encoded input with the provided AES secret key.
    *
    * @note Fails and throws many kinds of exception for unsupported key formats.
    * @return the decrypted string
    */
  def decrypt(key: SecretKey, input: String): String = {
    val cipher = getCipher
    cipher.init(DECRYPT_MODE, key)
    val bytes  = cipher.doFinal(Base64.getDecoder.decode(input))
    new String(bytes, UTF_8)
  }

  /**
    * Derives a suitable AES-256 secret key from a given password and a salt.
    *
    * @return a [[javax.crypto.SecretKey]] instance
    */
  def deriveKey(password: String, salt: String): SecretKey = {
    val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512")
    val spec    = new PBEKeySpec(password.toCharArray, salt.getBytes(UTF_8), 1000, 256)
    val key     = factory.generateSecret(spec)
    new SecretKeySpec(key.getEncoded, "AES")
  }
}
