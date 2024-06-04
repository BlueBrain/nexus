package ch.epfl.bluebrain.nexus.delta.plugins.storage.files.routes

import akka.http.scaladsl.model.Uri
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.routes.DelegateFilesRoutes.DelegationResponse
import ch.epfl.bluebrain.nexus.delta.rdf.syntax.iriStringContextSyntax
import ch.epfl.bluebrain.nexus.testkit.Generators
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import io.circe.Json
import io.circe.literal.JsonStringContext
import io.circe.syntax.EncoderOps
import munit.CatsEffectSuite

import scala.concurrent.duration.DurationInt

class TokenIssuerSuite extends CatsEffectSuite with Generators {

  val rsaJWK: RSAKey = new RSAKeyGenerator(2048).generate()

  test("JWS verification successfully round trips for identical payloads") {
    val tokenIssuer     = new TokenIssuer(rsaJWK, tokenValidity = 100.seconds)
    val returnedPayload = genPayload()

    for {
      jwsPayload          <- tokenIssuer.issueJWSPayload(returnedPayload.asJson)
      parsedSignedPayload <- tokenIssuer.verifyJWSPayload(jwsPayload)
    } yield assertEquals(parsedSignedPayload, returnedPayload.asJson)
  }

  def genPayload(): Json =
    json"""
        {
          "bucket": ${genString()},
          "id": ${genString()},
          "path": ${genString()}
        }
          """

  test("JWS verification fails if token is expired") {
    val tokenIssuer     = new TokenIssuer(rsaJWK, tokenValidity = 0.seconds)
    val returnedPayload = DelegationResponse(genString(), iri"potato", Uri(genString()), None)

    val program = for {
      jwsPayload <- tokenIssuer.issueJWSPayload(returnedPayload.asJson)
      _          <- IO.sleep(1.second)
      _          <- tokenIssuer.verifyJWSPayload(jwsPayload)
    } yield ()

    program.interceptMessage[Exception]("Token has expired")
  }
}
