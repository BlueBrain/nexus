package ch.epfl.bluebrain.nexus.delta.sdk.identities

import akka.http.scaladsl.model.headers.OAuth2BearerToken
import akka.http.scaladsl.model.{HttpRequest, Uri}
import cats.data.NonEmptySet
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.cache.CacheConfig
import ch.epfl.bluebrain.nexus.delta.sdk.generators.{RealmGen, WellKnownGen}
import ch.epfl.bluebrain.nexus.delta.sdk.http.HttpClientError.HttpUnexpectedError
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.TokenRejection.{AccessTokenDoesNotContainAnIssuer, AccessTokenDoesNotContainSubject, GetGroupsFromOidcError, InvalidAccessToken, InvalidAccessTokenFormat, UnknownAccessTokenIssuer}
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.{AuthToken, Caller}
import ch.epfl.bluebrain.nexus.delta.sdk.realms.model.Realm
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Anonymous, Authenticated, Group, User}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import ch.epfl.bluebrain.nexus.testkit.{CirceLiteral, EitherValuable, IOValues, TestHelpers}
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jose.{JWSAlgorithm, JWSHeader}
import com.nimbusds.jwt.{JWTClaimsSet, PlainJWT, SignedJWT}
import io.circe.{parser, Json}
import monix.bio.{IO, UIO}
import monix.execution.Scheduler.Implicits.global
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import java.time.Instant
import java.util.Date
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._

class IdentitiesImplSpec
    extends AnyWordSpecLike
    with Matchers
    with CirceLiteral
    with TestHelpers
    with IOValues
    with EitherValuable {

  /**
    * Generate RSA key
    */
  def generateKeys: RSAKey =
    new RSAKeyGenerator(2048)
      .keyID(genString())
      .generate()

  /**
    * Generate token
    */
  def generateToken(
      subject: String,
      issuer: String,
      rsaKey: RSAKey,
      expires: Instant = Instant.now().plusSeconds(3600),
      notBefore: Instant = Instant.now().minusSeconds(3600),
      aud: Option[NonEmptySet[String]] = None,
      groups: Option[Set[String]] = None,
      useCommas: Boolean = false,
      preferredUsername: Option[String] = None
  ): AuthToken = {
    val signer = new RSASSASigner(rsaKey.toPrivateKey)
    val csb    = new JWTClaimsSet.Builder()
      .issuer(issuer)
      .subject(subject)
      .expirationTime(Date.from(expires))
      .notBeforeTime(Date.from(notBefore))

    groups.foreach { set =>
      if (useCommas) csb.claim("groups", set.mkString(","))
      else csb.claim("groups", set.toArray)
    }

    aud.foreach(audiences => csb.audience(audiences.toList.asJava))

    preferredUsername.foreach(pu => csb.claim("preferred_username", pu))

    val jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(rsaKey.getKeyID).build(), csb.build())
    jwt.sign(signer)
    AuthToken(jwt.serialize())
  }

  private val rsaKey = generateKeys

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

  private val githubLabel                = Label.unsafe("github")
  private val githubLabel2               = Label.unsafe("github2")
  private val (githubOpenId, githubWk)   = WellKnownGen.create(githubLabel.value)
  private val (githubOpenId2, githubWk2) = WellKnownGen.create(githubLabel2.value)

  private val github = RealmGen
    .realm(githubOpenId, githubWk)
    .copy(
      keys = Set(parser.parse(rsaKey.toPublicJWK.toJSONString).rightValue)
    )

  private val github2 = RealmGen
    .realm(githubOpenId2, githubWk2, acceptedAudiences = Some(NonEmptySet.of("audience", "ba")))
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
    githubLabel.value  -> github,
    githubLabel2.value -> github2,
    gitlabLabel.value  -> gitlab
  )

  private def userInfo(uri: Uri): IO[HttpUnexpectedError, Json] =
    ioFromMap(
      Map(
        github.userInfoEndpoint -> json"""{ "groups": ["group3", "group4"] }"""
      ),
      (_: Uri) => HttpUnexpectedError(HttpRequest(), "Error while getting response")
    )(uri)

  private val identities: Identities = IdentitiesImpl(
    findActiveRealm,
    (uri: Uri, _: OAuth2BearerToken) => userInfo(uri),
    CacheConfig(10, 2.minutes)
  ).accepted

  "Identities" should {

    val auth   = Authenticated(githubLabel)
    val group1 = Group("group1", githubLabel)
    val group2 = Group("group2", githubLabel)
    val group3 = Group("group3", githubLabel)
    val group4 = Group("group4", githubLabel)

    "correctly extract the caller" in {
      val expires = Instant.now().plusSeconds(3600)
      val token   = generateToken(
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
      val token   = generateToken(
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
      val token   = generateToken(
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
      val token   = generateToken(
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

    "succeed when the token is valid and aud matches the available audiences" in {
      val expires = Instant.now().plusSeconds(3600)
      val token   = generateToken(
        subject = "Robert",
        issuer = githubLabel2.value,
        rsaKey = rsaKey,
        expires = expires,
        aud = Some(NonEmptySet.of("ca", "ba")),
        groups = Some(Set("group1", "group2"))
      )

      val user   = User("Robert", githubLabel2)
      val group1 = Group("group1", githubLabel2)
      val group2 = Group("group2", githubLabel2)
      identities.exchange(token).accepted shouldEqual
        Caller(user, Set(user, Anonymous, Authenticated(githubLabel2), group1, group2))
    }

    "fail when the token is valid but aud does not match the available audiences" in {
      val expires = Instant.now().plusSeconds(3600)
      val token   = generateToken(
        subject = "Robert",
        issuer = githubLabel2.value,
        rsaKey = rsaKey,
        expires = expires,
        aud = Some(NonEmptySet.of("ca", "de")),
        groups = Some(Set("group1", "group2"))
      )

      identities.exchange(token).rejected shouldEqual InvalidAccessToken(
        "Robert",
        githubLabel2.value,
        "JWT audience rejected: [ca, de]"
      )
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
      val token = generateToken(
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
      val token   = generateToken(
        subject = "Robert",
        issuer = githubLabel.value,
        rsaKey = rsaKey,
        expires = expires,
        groups = None,
        useCommas = true
      )

      identities.exchange(token).rejected shouldEqual InvalidAccessToken("Robert", githubLabel.value, "Expired JWT")
    }

    "fail when the token is not yet valid" in {
      val notBefore = Instant.now().plusSeconds(3600)
      val token     = generateToken(
        subject = "Robert",
        issuer = githubLabel.value,
        rsaKey = rsaKey,
        notBefore = notBefore,
        groups = None,
        useCommas = true
      )

      identities.exchange(token).rejected shouldEqual InvalidAccessToken(
        "Robert",
        githubLabel.value,
        "JWT before use time"
      )
    }

    "fail when the signature is invalid" in {
      val token = generateToken(
        subject = "Robert",
        issuer = githubLabel.value,
        rsaKey = generateKeys,
        groups = None,
        useCommas = true
      )

      identities.exchange(token).rejected shouldEqual InvalidAccessToken(
        "Robert",
        githubLabel.value,
        "Signed JWT rejected: Another algorithm expected, or no matching key(s) found"
      )
    }

    "fail when getting groups from the oidc provider can't be complete" in {
      val token = generateToken(
        subject = "Robert",
        issuer = gitlabLabel.value,
        rsaKey = rsaKey,
        groups = None,
        useCommas = true
      )

      identities.exchange(token).rejected shouldEqual GetGroupsFromOidcError("Robert", gitlabLabel.value)
    }
  }

}
