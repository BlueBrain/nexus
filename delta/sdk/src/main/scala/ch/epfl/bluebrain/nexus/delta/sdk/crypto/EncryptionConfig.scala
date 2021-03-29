package ch.epfl.bluebrain.nexus.delta.sdk.crypto

import ch.epfl.bluebrain.nexus.delta.kernel.Secret

/**
  * The encryption of sensitive fields configuration
  *
  * @param password the password for the symmetric-key cyphering algorithm
  * @param salt     the salt value
  */
final case class EncryptionConfig(password: Secret[String], salt: Secret[String]) {
  val crypto: Crypto = Crypto(password.value, salt.value)
}
