package ch.epfl.bluebrain.nexus.delta.sdk.jws

import cats.effect.{Clock, IO}
import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import ch.epfl.bluebrain.nexus.delta.sdk.jws.JWSError.{InvalidJWSPayload, JWSSignatureExpired, UnconfiguredJWS}
import com.nimbusds.jose.crypto.{RSASSASigner, RSASSAVerifier}
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.util.JSONObjectUtils
import com.nimbusds.jose.{JWSAlgorithm, JWSHeader, JWSObjectJSON, Payload}
import io.circe.{parser, Decoder, Json, Printer}

import scala.concurrent.duration.FiniteDuration
import scala.jdk.CollectionConverters.ListHasAsScala

sealed trait JWSPayloadHelper {

  def sign(payloadToSign: Json): IO[Json]

  def verify(payload: Json): IO[Json]

  def verifyAs[A: Decoder](payload: Json): IO[A] = verify(payload).flatMap { originalPayload =>
    IO.fromEither(originalPayload.as[A])
  }
}

object JWSPayloadHelper {

  final object JWSDisabled extends JWSPayloadHelper {

    override def sign(payloadToSign: Json): IO[Json] = IO.raiseError(UnconfiguredJWS)

    override def verify(payload: Json): IO[Json] = IO.raiseError(UnconfiguredJWS)
  }

  final class JWSPayloadHelperImpl(key: RSAKey, tokenValidity: FiniteDuration)(implicit clock: Clock[IO])
      extends JWSPayloadHelper {
    private val signer               = new RSASSASigner(key)
    private val verifier             = new RSASSAVerifier(key.toPublicJWK)
    private val TokenValiditySeconds = tokenValidity.toSeconds
    private val log                  = Logger[JWSPayloadHelper]

    def sign(payloadToSign: Json): IO[Json] =
      for {
        now        <- clock.realTimeInstant
        jwsObject   = mkJWSObject(payloadToSign)
        _          <- IO.delay(jwsObject.sign(mkJWSHeader(now.getEpochSecond + TokenValiditySeconds), signer))
        serialized <- IO.delay(jwsObject.serializeFlattened())
        json       <- IO.fromEither(parser.parse(serialized))
      } yield json

    def verify(payload: Json): IO[Json] =
      for {
        jwsObject       <- IO.delay(JWSObjectJSON.parse(payload.toString()))
        sig             <- IO.fromOption(jwsObject.getSignatures.asScala.headOption)(InvalidJWSPayload)
        _               <- IO.delay(sig.verify(verifier))
        objectPayload    = jwsObject.getPayload.toString
        originalPayload <- IO.fromEither(parser.parse(objectPayload))
        _               <- log.debug(s"Original payload parsed for token: $originalPayload")
        now             <- clock.realTimeInstant
        exp             <- IO.delay(sig.getHeader.getCustomParam("exp").asInstanceOf[Long])
        _               <- IO.raiseWhen(now.getEpochSecond > exp)(JWSSignatureExpired)
      } yield originalPayload

    private def mkJWSHeader(expSeconds: Long): JWSHeader =
      new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID).customParam("exp", expSeconds).build()

    private def mkJWSObject(payload: Json) = new JWSObjectJSON(mkPayload(payload))

    private def mkPayload(raw: Json) = {
      val jsonObjectMap = JSONObjectUtils.parse(raw.printWith(Printer.noSpacesSortKeys))
      new Payload(jsonObjectMap)
    }
  }
  def apply(config: JWSConfig): JWSPayloadHelper = config match {
    case JWSConfig.Disabled         => JWSDisabled
    case enabled: JWSConfig.Enabled => new JWSPayloadHelperImpl(enabled.rsaKey, enabled.ttl)
  }
}
