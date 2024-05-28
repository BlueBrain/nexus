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

  val rsaJWK: RSAKey = new RSAKeyGenerator(2048).generate()

  test("JWS verification successfully round trips for identical payloads") {
    val tokenIssuer     = new TokenIssuer(rsaJWK, tokenValidity = 100.seconds)
    val returnedPayload = DelegationResponse("mybucket", iri"potato", Uri(genString()))

    for {
      sig <- tokenIssuer.issueJWSToken(returnedPayload.asJson)
      _   <- tokenIssuer.verifyJWSToken(sig, returnedPayload.asJson)
    } yield ()
  }

  test("JWS verification fails if signature does not match payload") {
    val tokenIssuer     = new TokenIssuer(rsaJWK, tokenValidity = 100.seconds)
    val returnedPayload = DelegationResponse(genString(), iri"potato", Uri(genString()))
    val providedPayload = returnedPayload.copy(bucket = genString())

    val program = for {
      token <- tokenIssuer.issueJWSToken(returnedPayload.asJson)
      _     <- tokenIssuer.verifyJWSToken(token, providedPayload.asJson)
    } yield ()

    program.interceptMessage[Exception]("Token incompatible with payload")
  }

  test("JWS verification fails if token is expired") {
    val tokenIssuer     = new TokenIssuer(rsaJWK, tokenValidity = 0.seconds)
    val returnedPayload = DelegationResponse(genString(), iri"potato", Uri(genString()))

    val program = for {
      sig <- tokenIssuer.issueJWSToken(returnedPayload.asJson)
      _   <- IO.sleep(1.second)
      _   <- tokenIssuer.verifyJWSToken(sig, returnedPayload.asJson)
    } yield ()

    program.interceptMessage[Exception]("Token has expired")
  }

  /**
    * Steps used to generate private key in the test below. This was also used to store the real key in AWS.
    *   1. Generate RSA key: `openssl genrsa -out private_key.pem 2048` 2. Convert to PKCS#8 format: `openssl pkcs8
    * -topk8 -inform PEM -outform PEM -in private_key.pem -out private_key_pkcs8.pem -nocrypt` 3. Remove line breaks,
    * copy secret: `cat private_key_pkcs8.pem | tr -d '\n' | pbcopy`
    */
  test("Parsing RSA private key and JWS verification succeed") {
    val rawKey          =
      "-----BEGIN PRIVATE KEY-----MIIEvwIBADANBgkqhkiG9w0BAQEFAASCBKkwggSlAgEAAoIBAQDuzdiUC+Y74AfKSDO0hNiYacIdwz67Oevlhl8RiIq7A6kbnAvv/BC18EqzVTj8XKigW2fo9z1VNYQSHnvoZtqFsH+M4SD3cqg1KkpWniLNUlYQJ7jBKex8+3nJdjlzXRb+UWHTbrWVFsKW/p2bUOzBqmaxW3odW1IIRxSdTRTZkZ1+F49FG64fdNJxc47LQL+88KA/b1HHondZ7HljM9zrokmEH0h8qFyovdTuSdlTUA4d8ELoQQ1657eerbTjEJLJUQzy7ijob0jHE1s/CbbKGTgU1/S02v4ZIfdLRmuN1OPGT/Egh3cwv5YpG9dQwpx7qV0HVZd2F+AGFLgKvli3AgMBAAECggEAPF7duc+oTMq+LW1DZQeQmjdiU5PgcAScllH6Bry2FcE/JzOz8N+Qee5ddCi/V001dBSnmEWow7nbwZjjSkV2SQXtuPfRFb1uuMIQOQWRVsbR6xOfqXWny5DnoP66V2fZQEHisUjzrtUqLHIB9hnnQK6Ld5rgrDtB6cXOeFXcR4QDgLuZop/CN7yCOuSnlGLhlNIK+pyA7KhvkWZBvPheGJIbLubG+Lv4ISeo2v5H5M7JFmsAWX9Rc6Mqs258ILxK34NuhFt7heb1+gF3Ppe2O2i+KZ5gxJv2GDx9W92xLMF093mbGxo0PfF/whNsLlKGGd32nadCAfMSmLVAXUIdAQKBgQD4KeJQuwc7nOG1E2Zrh/dzl6GiqrNJZoHBG+iQyU8KwSpWRE/Tk5Ej0JWCBxBM/VNx/qge4pXeEs/vu+aZNj25buHhfA6mrCFLBWit/0Albl9dcJRTb+FEEmojiXaJZLw3TTxFxWKEXji8YWqB7ECHW02nvKrxAdjAD6W4elxlkQKBgQD2WE0QlU6X+OQM0AniuK3y5xNpq10LVAEAgSuKHKR+1kkqvEHUl/S4lGvojlx8V502zcIqIhrC1G3XHJwZF17Ybd4Uec8R+YQSwW2X3Qml1GMN53BXLgjD+VP8a3vfhuCHARJks3HYXf7py1oXMF0tiJ+v66RZ63QVhVTGqe6VxwKBgQCrv+kWsGowEsKPHK8cqsxSntXKC9Prb9tLd/I8Cmb+7XNMoxiQOKgRnnFqvVLFxelzkqhuP6kzOdfZdjUBQm3zoU8JTF+jcKvWDRdGnMqbXUj5FUpCeMLx5sC4eZGlQyeUKosVSqeFLuSbU9xvsL90LnePKF8yT3HgcrPh+iVqUQKBgQCJp0vg4V2ahCSCmFl9zC6/Vao+WNhUNSueKY+3zEuK6JjX/XxXnFXOMnmd6Lb7cEXUUuOVgZsslWGPW1hKmQmRrMr07B/ublwD0vw3aPc0J9r18QaQYJPbVl485Z7Bh++84Ldzd+Y8vkFsSQpdfNQEVpzMw8MB0BT81ZVKsbg1DwKBgQCTrtp+IzECCHR0yALElwREDjRLnQcc/Omnqlp6U9g/34GyFdKuJ/q9jZ0BteakveaunqI6rJbszn07UfKikaB+i3DmLzZadoAb2f/jSThAe/bR606wtlLUA4IEj3oZhXT+neSEboFK1IvRqGmKUrSWMIpJ/fjWKnS0hAoHbqdmpg==-----END PRIVATE KEY-----"
    val returnedPayload = DelegationResponse("mybucket", iri"potato", Uri(genString()))

    for {
      parsedPrivateKey <- IO.fromTry(TokenIssuer.parseRSAPrivateKey(rawKey))
      rsaKey            = TokenIssuer.generateRSAKeyFromPrivate(parsedPrivateKey)
      tokenIssuer       = new TokenIssuer(rsaKey, tokenValidity = 100.seconds)
      sig              <- tokenIssuer.issueJWSToken(returnedPayload.asJson)
      _                <- tokenIssuer.verifyJWSToken(sig, returnedPayload.asJson)
    } yield ()
  }

}
