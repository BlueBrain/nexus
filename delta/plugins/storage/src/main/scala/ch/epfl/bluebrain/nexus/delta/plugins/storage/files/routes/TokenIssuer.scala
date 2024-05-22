package ch.epfl.bluebrain.nexus.delta.plugins.storage.files.routes

import cats.effect.{Clock, IO}
import com.nimbusds.jose.crypto.{RSASSASigner, RSASSAVerifier}
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.{JWSAlgorithm, JWSHeader, JWSObject, Payload}
import io.circe.{Json, Printer}

class TokenIssuer(key: RSAKey, tokenValiditySeconds: Long)(implicit clock: Clock[IO]) {
  private val signer   = new RSASSASigner(key)
  private val verifier = new RSASSAVerifier(key.toPublicJWK)

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
    new JWSObject(mkJWSHeader(nowEpochSeconds + tokenValiditySeconds), mkPayload(payload))

  private def mkPayload(raw: Json) = new Payload(raw.printWith(Printer.noSpacesSortKeys))
}
