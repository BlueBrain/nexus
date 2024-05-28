package ch.epfl.bluebrain.nexus.delta.plugins.storage.files.routes

import cats.effect.{Clock, IO}
import com.nimbusds.jose.crypto.{RSASSASigner, RSASSAVerifier}
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.util.JSONObjectUtils
import com.nimbusds.jose.{JWSAlgorithm, JWSHeader, JWSObject, Payload}
import io.circe.{Json, Printer}

import java.security.KeyFactory
import java.security.interfaces.{RSAPrivateCrtKey, RSAPublicKey}
import java.security.spec.{PKCS8EncodedKeySpec, RSAPublicKeySpec}
import java.util.Base64
import scala.concurrent.duration.FiniteDuration
import scala.util.Try

class TokenIssuer(key: RSAKey, tokenValidity: FiniteDuration)(implicit clock: Clock[IO]) {
  private val signer               = new RSASSASigner(key)
  private val verifier             = new RSASSAVerifier(key.toPublicJWK)
  private val TokenValiditySeconds = tokenValidity.toSeconds

  def issueJWSToken(rawPayload: Json): IO[String] =
    for {
      now       <- clock.realTimeInstant
      jwsObject  = mkJWSObject(now.getEpochSecond, rawPayload)
      _         <- IO.delay(jwsObject.sign(signer))
      signature <- IO.delay(jwsObject.serialize())
    } yield signature

  def verifyJWSToken(token: String, rawPayload: Json): IO[Unit] =
    for {
      jwsObject       <- IO.delay(JWSObject.parse(token))
      _               <- IO.delay(jwsObject.verify(verifier))
      tokenPayload     = jwsObject.getPayload.toString
      candidatePayload = mkPayload(rawPayload).toString
      // TODO raise FileRejections w/ appropriate code
      _               <- IO.raiseWhen(candidatePayload != tokenPayload)(new Exception("Token incompatible with payload"))
      now             <- clock.realTimeInstant
      exp             <- IO.delay(jwsObject.getHeader.getCustomParam("exp").asInstanceOf[Long])
      _               <- IO.raiseWhen(now.getEpochSecond > exp)(new Exception("Token has expired"))
    } yield ()

  private def mkJWSHeader(expSeconds: Long): JWSHeader =
    new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID).customParam("exp", expSeconds).build()

  private def mkJWSObject(nowEpochSeconds: Long, payload: Json) =
    new JWSObject(mkJWSHeader(nowEpochSeconds + TokenValiditySeconds), mkPayload(payload))

  private def mkPayload(raw: Json) = {
    val jsonObjectMap = JSONObjectUtils.parse(raw.printWith(Printer.noSpacesSortKeys))
    new Payload(jsonObjectMap)
  }
}

object TokenIssuer {

  def generateRSAKeyFromPrivate(privateKey: RSAPrivateCrtKey): RSAKey = {
    val publicKeySpec: RSAPublicKeySpec = new RSAPublicKeySpec(privateKey.getModulus, privateKey.getPublicExponent)
    val kf                              = KeyFactory.getInstance("RSA")
    val publicKey                       = kf.generatePublic(publicKeySpec).asInstanceOf[RSAPublicKey]
    new RSAKey.Builder(publicKey).privateKey(privateKey).build()
  }

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

}
