package ch.epfl.bluebrain.nexus.delta.sdk.identities.model

import akka.http.scaladsl.model.StatusCodes
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClassUtils
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.BNode
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.HttpResponseFields
import io.circe.syntax._
import io.circe.{Encoder, JsonObject}

/**
  * Enumeration of token rejections.
  *
  * @param reason
  *   a descriptive message for reasons why a token is rejected by the system
  */
sealed abstract class TokenRejection(val reason: String) extends Product with Serializable

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
    * Rejection for cases where the access token is invalid according to JWTClaimsVerifier
    */
  final case class InvalidAccessToken(subject: String, issuer: String, details: String)
      extends TokenRejection(s"The provided token is invalid for user '$subject/$issuer' .")

  /**
    * Rejection for cases where we couldn't fetch the groups from the OIDC provider
    */
  final case class GetGroupsFromOidcError(subject: String, issuer: String)
      extends TokenRejection(
        "The token is invalid; possible causes are: the OIDC provider is unreachable."
      )

  implicit val tokenRejectionEncoder: Encoder.AsObject[TokenRejection] =
    Encoder.AsObject.instance { r =>
      val tpe  = ClassUtils.simpleName(r)
      val json = JsonObject.empty.add(keywords.tpe, tpe.asJson).add("reason", r.reason.asJson)
      r match {
        case InvalidAccessToken(_, _, error) => json.add("details", error.asJson)
        case _                               => json
      }
    }

  implicit final val tokenRejectionJsonLdEncoder: JsonLdEncoder[TokenRejection] =
    JsonLdEncoder.computeFromCirce(id = BNode.random, ctx = ContextValue(contexts.error))

  implicit val responseFieldsTokenRejection: HttpResponseFields[TokenRejection] =
    HttpResponseFields(_ => StatusCodes.Unauthorized)
}
