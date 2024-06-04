package ch.epfl.bluebrain.nexus.delta.plugins.storage.files.routes

import cats.effect.{Clock, IO}
import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import com.nimbusds.jose.crypto.{RSASSASigner, RSASSAVerifier}
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.util.JSONObjectUtils
import com.nimbusds.jose.{JWSAlgorithm, JWSHeader, JWSObjectJSON, Payload}
import io.circe.{parser, Json, Printer}

import java.security.KeyFactory
import java.security.interfaces.{RSAPrivateCrtKey, RSAPublicKey}
import java.security.spec.{PKCS8EncodedKeySpec, RSAPublicKeySpec}
import java.util.Base64
import scala.concurrent.duration.FiniteDuration
import scala.jdk.CollectionConverters.ListHasAsScala
import scala.util.Try

class TokenIssuer(key: RSAKey, tokenValidity: FiniteDuration)(implicit clock: Clock[IO]) {
  private val signer               = new RSASSASigner(key)
  private val verifier             = new RSASSAVerifier(key.toPublicJWK)
  private val TokenValiditySeconds = tokenValidity.toSeconds
  private val log                  = Logger[TokenIssuer]

  def issueJWSPayload(payloadToSign: Json): IO[Json] =
    for {
      now        <- clock.realTimeInstant
      jwsObject   = mkJWSObject(payloadToSign)
      _          <- IO.delay(jwsObject.sign(mkJWSHeader(now.getEpochSecond + TokenValiditySeconds), signer))
      serialized <- IO.delay(jwsObject.serializeFlattened())
      json       <- IO.fromEither(parser.parse(serialized))
    } yield json

  def verifyJWSPayload(payload: Json): IO[Json] =
    for {
      jwsObject       <- IO.delay(JWSObjectJSON.parse(payload.toString()))
      sig             <- IO.fromOption(jwsObject.getSignatures.asScala.headOption)(new Exception("Signature missing"))
      _               <- IO.delay(sig.verify(verifier))
      _               <- log.info(s"Signature verified against payload")
      objectPayload    = jwsObject.getPayload.toString
      _               <- log.info(s"Object payload is $objectPayload")
      originalPayload <- IO.fromEither(parser.parse(objectPayload))
      _               <- log.info(s"Original payload parsed: $originalPayload")
      now             <- clock.realTimeInstant
      exp             <- IO.delay(sig.getHeader.getCustomParam("exp").asInstanceOf[Long])
      _               <- IO.raiseWhen(now.getEpochSecond > exp)(new Exception("Token has expired"))
    } yield originalPayload

  private def mkJWSHeader(expSeconds: Long): JWSHeader =
    new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID).customParam("exp", expSeconds).build()

  private def mkJWSObject(payload: Json) = new JWSObjectJSON(mkPayload(payload))

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
