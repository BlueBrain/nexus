package ch.epfl.bluebrain.nexus.delta.service

import java.time.Instant
import java.util.Date

import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.AuthToken
import ch.epfl.bluebrain.nexus.testkit.TestHelpers
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jose.{JWSAlgorithm, JWSHeader}
import com.nimbusds.jwt.{JWTClaimsSet, SignedJWT}

object TokenGenerator extends TestHelpers {

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
  def token(
      subject: String,
      issuer: String,
      rsaKey: RSAKey,
      expires: Instant = Instant.now().plusSeconds(3600),
      notBefore: Instant = Instant.now().minusSeconds(3600),
      groups: Option[Set[String]] = None,
      useCommas: Boolean = false,
      preferredUsername: Option[String] = None
  ) = {
    val signer = new RSASSASigner(rsaKey.toPrivateKey)
    val csb    = new JWTClaimsSet.Builder()
      .issuer(issuer)
      .subject(subject)
      .expirationTime(Date.from(expires))
      .notBeforeTime(Date.from(notBefore))

    groups.map { set =>
      if (useCommas) csb.claim("groups", set.mkString(","))
      else csb.claim("groups", set.toArray)
    }

    preferredUsername.map { pu => csb.claim("preferred_username", pu) }

    val jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(rsaKey.getKeyID).build(), csb.build())
    jwt.sign(signer)
    AuthToken(jwt.serialize())
  }

}
