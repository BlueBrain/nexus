package ch.epfl.bluebrain.nexus.storage.routes

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers.OAuth2BearerToken
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.testkit.ScalatestRouteTest
import ch.epfl.bluebrain.nexus.storage.auth.AuthorizationMethod
import ch.epfl.bluebrain.nexus.storage.auth.AuthorizationMethod.VerifyToken
import ch.epfl.bluebrain.nexus.storage.config.AppConfig.HttpConfig
import ch.epfl.bluebrain.nexus.storage.config.Settings
import ch.epfl.bluebrain.nexus.storage.routes.AuthDirectives._
import ch.epfl.bluebrain.nexus.storage.utils.Randomness.genString
import ch.epfl.bluebrain.nexus.testkit.jwt.TokenGenerator
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jose.jwk.{JWKSet, RSAKey}
import org.scalatest.BeforeAndAfter
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import java.time.Instant

//noinspection NameBooleanParameters
class AuthDirectivesSpec extends AnyWordSpecLike with Matchers with BeforeAndAfter with ScalatestRouteTest {

  implicit private val hc: HttpConfig = Settings(system).appConfig.http

  def validateRoute(implicit authorizationMethod: AuthorizationMethod) = Routes.wrap(validUser.apply {
    complete("")
  })

  "Validating with the anonymous method" should {

    implicit val anonymousMethod: AuthorizationMethod = AuthorizationMethod.Anonymous
    "validate any token" in {
      val expected = "token"
      Get("/").addCredentials(OAuth2BearerToken(expected)) ~> validateRoute ~> check {
        status shouldEqual StatusCodes.OK
      }
    }
    "validate if no token is provided" in {
      Get("/") ~> validateRoute ~> check {
        status shouldEqual StatusCodes.OK
      }
    }
  }

  "Validating with the verify token method" should {

    def generateKey: RSAKey = new RSAKeyGenerator(2048).keyID(genString()).generate()

    val rsaKey       = generateKey
    val validIssuer  = "bbp"
    val validSubject = "admin"

    def generateToken(subject: String, issuer: String, rsaKey: RSAKey) =
      TokenGenerator
        .generateToken(
          subject,
          issuer,
          rsaKey,
          Instant.now().plusSeconds(100L),
          Instant.now().minusSeconds(100L),
          None,
          None,
          false,
          Some(subject)
        )
        .value

    implicit val verifyTokenMethod: AuthorizationMethod =
      VerifyToken(validIssuer, validSubject, None, new JWKSet(rsaKey.toPublicJWK))

    "Succeed with a valid token" in {
      val token = generateToken(validSubject, validIssuer, rsaKey)
      Get("/").addCredentials(OAuth2BearerToken(token)) ~> validateRoute ~> check {
        status shouldEqual StatusCodes.OK
      }
    }

    "Fail with an invalid issuer" in {
      val token = generateToken(validSubject, "xxx", rsaKey)
      Get("/").addCredentials(OAuth2BearerToken(token)) ~> validateRoute ~> check {
        status shouldEqual StatusCodes.Unauthorized
      }
    }

    "Fail with an invalid subject" in {
      val token = generateToken("bob", validIssuer, rsaKey)
      Get("/").addCredentials(OAuth2BearerToken(token)) ~> validateRoute ~> check {
        status shouldEqual StatusCodes.Unauthorized
      }
    }

    "Fail with a token signed with another key" in {
      val anotherKey: RSAKey = generateKey
      val token              = generateToken(validSubject, validIssuer, anotherKey)
      Get("/").addCredentials(OAuth2BearerToken(token)) ~> validateRoute ~> check {
        status shouldEqual StatusCodes.Unauthorized
      }
    }

    "Fail with an invalid token" in {
      val token = "token"
      Get("/").addCredentials(OAuth2BearerToken(token)) ~> validateRoute ~> check {
        status shouldEqual StatusCodes.Unauthorized
      }
    }
  }
}
