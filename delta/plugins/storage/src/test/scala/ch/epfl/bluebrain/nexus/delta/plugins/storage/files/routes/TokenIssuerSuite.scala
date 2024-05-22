package ch.epfl.bluebrain.nexus.delta.plugins.storage.files.routes

import akka.http.scaladsl.model.Uri
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.routes.DelegateFilesRoutes.DelegationResponse
import ch.epfl.bluebrain.nexus.delta.rdf.syntax.iriStringContextSyntax
import ch.epfl.bluebrain.nexus.testkit.Generators
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import io.circe.syntax.EncoderOps
import munit.CatsEffectSuite

import scala.concurrent.duration.DurationInt

class TokenIssuerSuite extends CatsEffectSuite with Generators {

  val rsaJWK: RSAKey = new RSAKeyGenerator(2048).keyID("123").generate()

  test("JWS verification successfully round trips for identical payloads") {
    val tokenIssuer     = new TokenIssuer(rsaJWK, tokenValiditySeconds = 100)
    val returnedPayload = DelegationResponse("mybucket", iri"potato", Uri(genString()))

    for {
      sig <- tokenIssuer.issueJWSToken(returnedPayload.asJson)
      _   <- tokenIssuer.verifyJWSToken(sig, returnedPayload.asJson)
    } yield ()
  }

  test("JWS verification fails if signature does not match payload") {
    val tokenIssuer     = new TokenIssuer(rsaJWK, tokenValiditySeconds = 100)
    val returnedPayload = DelegationResponse(genString(), iri"potato", Uri(genString()))
    val providedPayload = returnedPayload.copy(bucket = genString())

    val program = for {
      token <- tokenIssuer.issueJWSToken(returnedPayload.asJson)
      _     <- tokenIssuer.verifyJWSToken(token, providedPayload.asJson)
    } yield ()

    program.interceptMessage[Exception]("Token incompatible with payload")
  }

  test("JWS verification fails if token is expired") {
    val tokenIssuer     = new TokenIssuer(rsaJWK, tokenValiditySeconds = 0)
    val returnedPayload = DelegationResponse(genString(), iri"potato", Uri(genString()))

    val program = for {
      sig <- tokenIssuer.issueJWSToken(returnedPayload.asJson)
      _   <- IO.sleep(1.second)
      _   <- tokenIssuer.verifyJWSToken(sig, returnedPayload.asJson)
    } yield ()

    program.interceptMessage[Exception]("Token has expired")
  }

}
