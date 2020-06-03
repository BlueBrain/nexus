package ch.epfl.bluebrain.nexus.iam.auth

import com.github.ghik.silencer.silent
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredEncoder
import io.circe.{Encoder, Json}

/**
  * Enumeration of token rejections.
  *
  * @param msg a descriptive message for reasons why a token is rejected by the system
  */
sealed abstract class TokenRejection(val msg: String) extends Product with Serializable

object TokenRejection {

  /**
    * Rejection for cases where the AccessToken is not a properly formatted signed JWT.
    */
  final case object InvalidAccessTokenFormat
      extends TokenRejection(
        "Access token is invalid; possible causes are: JWT not signed, encoded parts are not properly encoded or each part is not a valid json."
      )

  /**
    * Rejection for cases where the access token does not contain a subject in the claim set.
    */
  final case object AccessTokenDoesNotContainSubject extends TokenRejection("The token doesn't contain a subject.")

  /**
    * Rejection for cases where the access token does not contain an issuer in the claim set.
    */
  final case object AccessTokenDoesNotContainAnIssuer extends TokenRejection("The token doesn't contain an issuer.")

  /**
    * Rejection for cases where the issuer specified in the access token claim set is unknown; also applies to issuers
    * of deprecated realms.
    */
  final case object UnknownAccessTokenIssuer extends TokenRejection("The issuer referenced in the token was not found.")

  /**
    *  Rejection for cases where the access token is invalid: incorrect signature or the ''not before'' and
    *  ''expiration'' values are incorrect with respect to the current timestamp.
    */
  final case object InvalidAccessToken
      extends TokenRejection(
        "The token is invalid; possible causes are: incorrect signature, the token is expired or the 'nbf' value was not met."
      )

  @silent // rejectionConfig is not recognized as being used
  implicit val tokenRejectionEncoder: Encoder[TokenRejection] = {
    implicit val rejectionConfig: Configuration = Configuration.default.withDiscriminator("@type")
    val enc                                     = deriveConfiguredEncoder[TokenRejection]
    Encoder.instance(r => enc(r) deepMerge Json.obj("reason" -> Json.fromString(r.msg)))
  }
}
