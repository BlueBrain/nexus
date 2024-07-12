package ch.epfl.bluebrain.nexus.delta.plugins.storage.jws

import com.nimbusds.jose.jwk.RSAKey

import java.security.KeyFactory
import java.security.interfaces.{RSAPrivateCrtKey, RSAPublicKey}
import java.security.spec.{PKCS8EncodedKeySpec, RSAPublicKeySpec}
import java.util.Base64
import scala.util.Try

object RSAUtils {

  def parseRSAPrivateKey(raw: String): Try[RSAPrivateCrtKey] = Try {
    val keyStripped        = raw
      .replace("-----END PRIVATE KEY-----", "")
      .replace("-----BEGIN PRIVATE KEY-----", "")
      .replace("\n", "")
    val keyStrippedDecoded = Base64.getDecoder.decode(keyStripped)

    val keySpec = new PKCS8EncodedKeySpec(keyStrippedDecoded)
    val kf      = KeyFactory.getInstance("RSA")
    kf.generatePrivate(keySpec).asInstanceOf[RSAPrivateCrtKey]
  }

  def generateRSAKeyFromPrivate(privateKey: RSAPrivateCrtKey): RSAKey = {
    val publicKeySpec: RSAPublicKeySpec = new RSAPublicKeySpec(privateKey.getModulus, privateKey.getPublicExponent)
    val kf                              = KeyFactory.getInstance("RSA")
    val publicKey                       = kf.generatePublic(publicKeySpec).asInstanceOf[RSAPublicKey]
    new RSAKey.Builder(publicKey).privateKey(privateKey).build()
  }

}
