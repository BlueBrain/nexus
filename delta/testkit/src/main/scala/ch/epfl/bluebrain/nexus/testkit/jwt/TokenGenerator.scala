package ch.epfl.bluebrain.nexus.testkit.jwt

import cats.data.NonEmptySet
import cats.syntax.all.*
import ch.epfl.bluebrain.nexus.delta.kernel.jwt.AuthToken
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.{JWSAlgorithm, JWSHeader}
import com.nimbusds.jwt.{JWTClaimsSet, SignedJWT}

import java.time.Instant
import java.util.Date

object TokenGenerator {

  import scala.jdk.CollectionConverters.*

  def generateToken(
      subject: String,
      issuer: String,
      rsaKey: RSAKey,
      expires: Instant,
      notBefore: Instant,
      aud: Option[NonEmptySet[String]] = None,
      groups: Option[Set[String]] = None,
      useCommas: Boolean = false,
      preferredUsername: Option[String] = None
  ): AuthToken = {
    val csb = new JWTClaimsSet.Builder()
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

    toSignedJwt(csb, rsaKey, new RSASSASigner(rsaKey.toPrivateKey))
  }

  def toSignedJwt(builder: JWTClaimsSet.Builder, rsaKey: RSAKey, signer: RSASSASigner): AuthToken = {
    val jwt = new SignedJWT(
      new JWSHeader.Builder(JWSAlgorithm.RS256)
        .keyID(rsaKey.getKeyID)
        .build(),
      builder.build()
    )
    jwt.sign(signer)
    AuthToken(jwt.serialize())
  }

}
