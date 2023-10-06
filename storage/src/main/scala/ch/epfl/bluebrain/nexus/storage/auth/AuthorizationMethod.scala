package ch.epfl.bluebrain.nexus.storage.auth

import cats.data.{NonEmptyList, NonEmptySet}
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.kernel.jwt.{AuthToken, ParsedToken}
import ch.epfl.bluebrain.nexus.storage.auth.AuthorizationError._
import com.nimbusds.jose.jwk.{JWK, JWKSet}
import pureconfig.ConfigReader
import pureconfig.generic.semiauto.deriveReader
import pureconfig.module.cats._

import scala.annotation.nowarn
import scala.jdk.CollectionConverters._
import scala.util.Try

/**
  * Authorization config
  */
sealed trait AuthorizationMethod {

  /**
    * Validates the incoming token
    */
  def validate(token: Option[AuthToken]): Either[AuthorizationError, Unit]
}

object AuthorizationMethod {

  /**
    * No token/authorization is needed when performing calls
    */
  final case object Anonymous extends AuthorizationMethod {
    override def validate(token: Option[AuthToken]): Either[AuthorizationError, Unit] = Right(())
  }

  /**
    * A token matching this realm and username is required and can be validated to the provided audiences and set of
    * JSON Web Keys
    */
  final case class VerifyToken(issuer: String, subject: String, audiences: Option[NonEmptySet[String]], keys: JWKSet)
      extends AuthorizationMethod {
    override def validate(token: Option[AuthToken]): Either[AuthorizationError, Unit] = {
      for {
        token       <- token.toRight(NoToken)
        parsedToken <- ParsedToken.fromToken(token).leftMap(InvalidToken)
        _           <- Either.cond(
                         issuer == parsedToken.issuer && subject == parsedToken.subject,
                         (),
                         UnauthorizedUser(parsedToken.issuer, parsedToken.subject)
                       )
        _           <- parsedToken.validate(audiences, keys).leftMap(TokenNotVerified)
      } yield ()
    }
  }

  @nowarn("cat=unused")
  implicit val authorizationMethodConfigReader: ConfigReader[AuthorizationMethod] = {
    implicit val jwkReader: ConfigReader[JWK]           = ConfigReader.fromStringTry { s => Try(JWK.parse(s)) }
    implicit val jwkSetReader: ConfigReader[JWKSet]     = ConfigReader[NonEmptyList[JWK]].map { l =>
      new JWKSet(l.toList.asJava)
    }
    implicit val anonymousReader                        = deriveReader[Anonymous.type]
    implicit val verifyToken: ConfigReader[VerifyToken] = deriveReader[VerifyToken]

    deriveReader[AuthorizationMethod]
  }

}
