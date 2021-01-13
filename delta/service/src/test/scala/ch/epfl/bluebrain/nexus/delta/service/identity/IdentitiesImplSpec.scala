package ch.epfl.bluebrain.nexus.delta.service.identity

import java.time.Instant
import java.util.Date

import akka.http.scaladsl.model.headers.OAuth2BearerToken
import akka.http.scaladsl.model.{HttpRequest, Uri}
import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategyConfig
import ch.epfl.bluebrain.nexus.delta.sdk.generators.{RealmGen, WellKnownGen}
import ch.epfl.bluebrain.nexus.delta.sdk.http.HttpClientError.HttpUnexpectedError
import ch.epfl.bluebrain.nexus.delta.sdk.model.Label
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.{Anonymous, Authenticated, Group, User}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.TokenRejection.{AccessTokenDoesNotContainAnIssuer, AccessTokenDoesNotContainSubject, GetGroupsFromOidcError, InvalidAccessToken, InvalidAccessTokenFormat, UnknownAccessTokenIssuer}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.{AuthToken, Caller}
import ch.epfl.bluebrain.nexus.delta.sdk.model.realms.Realm
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.{AbstractDBSpec, ConfigFixtures}
import ch.epfl.bluebrain.nexus.delta.service.TokenGenerator
import ch.epfl.bluebrain.nexus.testkit.{CirceLiteral, EitherValuable, IOValues, TestHelpers}
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.{JWSAlgorithm, JWSHeader}
import com.nimbusds.jwt.{JWTClaimsSet, PlainJWT, SignedJWT}
import io.circe.{parser, Json}
import monix.bio.{IO, UIO}
import monix.execution.Scheduler.Implicits.global
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration._

class IdentitiesImplSpec
    extends AbstractDBSpec
    with Matchers
    with CirceLiteral
    with TestHelpers
    with IOValues
    with EitherValuable
    with ConfigFixtures {

  private val rsaKey = TokenGenerator.generateKeys

  private val signer = new RSASSASigner(rsaKey.toPrivateKey)

  private def toSignedJwt(builder: JWTClaimsSet.Builder): AuthToken = {
    val jwt = new SignedJWT(
      new JWSHeader.Builder(JWSAlgorithm.RS256)
        .keyID(rsaKey.getKeyID)
        .build(),
      builder.build()
    )
    jwt.sign(signer)
    AuthToken(jwt.serialize())
  }

  private val githubLabel              = Label.unsafe("github")
  private val (githubOpenId, githubWk) = WellKnownGen.create(githubLabel.value)

  private val github = RealmGen
    .realm(githubOpenId, githubWk)
    .copy(
      keys = Set(parser.parse(rsaKey.toPublicJWK.toJSONString).rightValue)
    )

  private val gitlabLabel              = Label.unsafe("gitlab")
  private val (gitlabOpenId, gitlabWk) = WellKnownGen.create(gitlabLabel.value)

  private val gitlab = RealmGen
    .realm(gitlabOpenId, gitlabWk)
    .copy(
      keys = Set(parser.parse(rsaKey.toPublicJWK.toJSONString).rightValue)
    )

  private val findActiveRealm: String => UIO[Option[Realm]] = ioFromMap[String, Realm](
    githubLabel.value -> github,
    gitlabLabel.value -> gitlab
  )

  private def userInfo(uri: Uri): IO[HttpUnexpectedError, Json] =
    ioFromMap(
      Map(
        github.userInfoEndpoint -> json"""{ "groups": ["group3", "group4"] }"""
      ),
      (_: Uri) => HttpUnexpectedError(HttpRequest(), "Error while getting response")
    )(uri)

  private val groupsConfig = GroupsConfig(
    aggregate,
    RetryStrategyConfig.AlwaysGiveUp,
    2.minutes
  )

  private val identities: IdentitiesImpl = IdentitiesImpl(
    findActiveRealm,
    (uri: Uri, _: OAuth2BearerToken) => userInfo(uri),
    groupsConfig
  ).runSyncUnsafe()

  "Identities" should {

    val auth   = Authenticated(githubLabel)
    val group1 = Group("group1", githubLabel)
    val group2 = Group("group2", githubLabel)
    val group3 = Group("group3", githubLabel)
    val group4 = Group("group4", githubLabel)

    "correctly extract the caller" in {
      val expires = Instant.now().plusSeconds(3600)
      val token   = TokenGenerator.token(
        subject = "Robert",
        issuer = githubLabel.value,
        rsaKey = rsaKey,
        expires = expires,
        groups = Some(Set("group1", "group2")),
        preferredUsername = Some("Bob")
      )

      val user = User("Bob", githubLabel)
      identities.exchange(token).accepted shouldEqual Caller(user, Set(user, Anonymous, auth, group1, group2))
    }

    "succeed when the token is valid and preferred user name is not set" in {
      val expires = Instant.now().plusSeconds(3600)
      val token   = TokenGenerator.token(
        subject = "Robert",
        issuer = githubLabel.value,
        rsaKey = rsaKey,
        expires = expires,
        groups = Some(Set("group1", "group2"))
      )

      val user = User("Robert", githubLabel)
      identities.exchange(token).accepted shouldEqual
        Caller(user, Set(user, Anonymous, auth, group1, group2))
    }

    "succeed when the token is valid and groups are comma delimited" in {
      val expires = Instant.now().plusSeconds(3600)
      val token   = TokenGenerator.token(
        subject = "Robert",
        issuer = githubLabel.value,
        rsaKey = rsaKey,
        expires = expires,
        groups = Some(Set("group1", "group2")),
        useCommas = true
      )

      val user = User("Robert", githubLabel)
      identities.exchange(token).accepted shouldEqual
        Caller(user, Set(user, Anonymous, auth, group1, group2))
    }

    "succeed when the token is valid and groups are defined" in {
      val expires = Instant.now().plusSeconds(3600)
      val token   = TokenGenerator.token(
        subject = "Robert",
        issuer = githubLabel.value,
        rsaKey = rsaKey,
        expires = expires,
        groups = None,
        useCommas = true
      )

      val user = User("Robert", githubLabel)
      identities.exchange(token).accepted shouldEqual
        Caller(user, Set(user, Anonymous, auth, group3, group4))
    }

    "fail when the token is invalid" in {
      identities.exchange(AuthToken(genString())).rejected shouldEqual InvalidAccessTokenFormat
    }

    "fail when the token is not signed" in {
      val csb = new JWTClaimsSet.Builder()
        .subject("subject")
        .expirationTime(Date.from(Instant.now().plusSeconds(3600)))

      identities
        .exchange(AuthToken(new PlainJWT(csb.build()).serialize()))
        .rejected shouldEqual InvalidAccessTokenFormat
    }

    "fail when the token doesn't contain an issuer" in {
      val csb = new JWTClaimsSet.Builder()
        .subject("subject")
        .expirationTime(Date.from(Instant.now().plusSeconds(3600)))

      identities.exchange(toSignedJwt(csb)).rejected shouldEqual AccessTokenDoesNotContainAnIssuer
    }

    "fail when the token doesn't contain a subject" in {
      val csb = new JWTClaimsSet.Builder()
        .issuer(githubLabel.value)
        .expirationTime(Date.from(Instant.now().plusSeconds(3600)))

      identities.exchange(toSignedJwt(csb)).rejected shouldEqual AccessTokenDoesNotContainSubject
    }

    "fail when the token doesn't contain a known issuer" in {
      val token = TokenGenerator.token(
        subject = "Robert",
        issuer = "unoknown",
        rsaKey = rsaKey,
        groups = None,
        useCommas = true
      )

      identities.exchange(token).rejected shouldEqual UnknownAccessTokenIssuer
    }

    "fail when the token is expired" in {
      val expires = Instant.now().minusSeconds(3600)
      val token   = TokenGenerator.token(
        subject = "Robert",
        issuer = githubLabel.value,
        rsaKey = rsaKey,
        expires = expires,
        groups = None,
        useCommas = true
      )

      identities.exchange(token).rejected shouldEqual InvalidAccessToken
    }

    "fail when the token is not yet valid" in {
      val notBefore = Instant.now().plusSeconds(3600)
      val token     = TokenGenerator.token(
        subject = "Robert",
        issuer = githubLabel.value,
        rsaKey = rsaKey,
        notBefore = notBefore,
        groups = None,
        useCommas = true
      )

      identities.exchange(token).rejected shouldEqual InvalidAccessToken
    }

    "fail when the signature is invalid" in {
      val token = TokenGenerator.token(
        subject = "Robert",
        issuer = githubLabel.value,
        rsaKey = TokenGenerator.generateKeys,
        groups = None,
        useCommas = true
      )

      identities.exchange(token).rejected shouldEqual InvalidAccessToken
    }

    "fail when getting groups from the oidc provider can't be complete" in {
      val token = TokenGenerator.token(
        subject = "Robert",
        issuer = gitlabLabel.value,
        rsaKey = rsaKey,
        groups = None,
        useCommas = true
      )

      identities.exchange(token).rejected shouldEqual GetGroupsFromOidcError
    }
  }

}
