package ch.epfl.bluebrain.nexus.delta.service.identity

import java.time.Instant

import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.TokenRejection._
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.{AuthToken, TokenRejection}
import com.nimbusds.jwt.{JWTClaimsSet, SignedJWT}

import scala.util.Try

/**
  * Token where we extracted and validated the information needed from the [[jwtToken]]
  */
final case class ParsedToken private (
    rawToken: String,
    subject: String,
    issuer: String,
    expirationTime: Instant,
    groups: Option[Set[String]],
    jwtToken: SignedJWT
)

object ParsedToken {

  /**
    * Parse token and try to extract expected information from it
    *
    * @param token the raw token
    */
  private[identity] def fromToken(token: AuthToken): Either[TokenRejection, ParsedToken] = {

    def parseJwt: Either[TokenRejection, SignedJWT] =
      Either
        .catchNonFatal(SignedJWT.parse(token.value))
        .leftMap(_ => InvalidAccessTokenFormat)

    def claims(jwt: SignedJWT): Either[TokenRejection, JWTClaimsSet] =
      Either
        .catchNonFatal(jwt.getJWTClaimsSet)
        .filterOrElse(_ != null, InvalidAccessTokenFormat)
        .leftMap(_ => InvalidAccessTokenFormat)

    def subject(claimsSet: JWTClaimsSet) = {
      val preferredUsername = Try(claimsSet.getStringClaim("preferred_username"))
        .filter(_ != null)
        .toOption
      (preferredUsername orElse Option(claimsSet.getSubject)).toRight(AccessTokenDoesNotContainSubject)
    }

    def issuer(claimsSet: JWTClaimsSet): Either[TokenRejection, String] =
      Either.fromOption(Option(claimsSet.getIssuer), AccessTokenDoesNotContainAnIssuer)

    def groups(claimsSet: JWTClaimsSet): Option[Set[String]] =
      Option.when(
        claimsSet.getClaims.containsKey("groups")
      ) {
        import scala.jdk.CollectionConverters._
        Try(claimsSet.getStringListClaim("groups").asScala.toList)
          .filter(_ != null)
          .map(_.map(_.trim))
          .map(_.filterNot(_.isEmpty))
          .recoverWith { case _ => Try(claimsSet.getStringClaim("groups").split(",").map(_.trim).toList) }
          .toOption
          .map(_.toSet)
          .getOrElse(Set.empty)
      }

    for {
      jwt            <- parseJwt
      claimsSet      <- claims(jwt)
      subject        <- subject(claimsSet)
      issuer         <- issuer(claimsSet)
      expirationTime <- Either.right(claimsSet.getExpirationTime.toInstant)
      groups         <- Either.right(groups(claimsSet))
    } yield ParsedToken(token.value, subject, issuer, expirationTime, groups, jwt)
  }
}
