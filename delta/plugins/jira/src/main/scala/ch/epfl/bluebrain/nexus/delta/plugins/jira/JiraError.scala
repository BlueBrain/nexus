package ch.epfl.bluebrain.nexus.delta.plugins.jira

import akka.http.scaladsl.model.StatusCodes
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClassUtils
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.error.SDKError
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.HttpResponseFields
import io.circe.syntax.EncoderOps
import io.circe.{Encoder, JsonObject}

/**
  * OAuth related errors when exchanging with Jira
  */
sealed abstract class JiraError(reason: String, details: Option[String] = None) extends SDKError {
  final override def getMessage: String = details.fold(reason)(d => s"$reason\nDetails: $d")
}

object JiraError {

  /**
    * No token has been found for the current user
    */
  final case object NoTokenError extends JiraError("No token has been found for the current user.")

  /**
    * A request token was expected for the current user
    */
  final case object RequestTokenExpected extends JiraError("A request token was expected for the current user.")

  /**
    */
  final case object AccessTokenExpected extends JiraError("An access token was expected for the current user.")

  /**
    * Unknown error
    */
  final case class UnknownError(reason: String, details: Option[String] = None) extends JiraError(reason, details)

  implicit val jiraErrorEncoder: Encoder.AsObject[JiraError] =
    Encoder.AsObject.instance { e =>
      val tpe = ClassUtils.simpleName(e)
      JsonObject.empty.add(keywords.tpe, tpe.asJson).add("reason", e.getMessage.asJson)
    }

  implicit final val jiraErrorJsonLdEncoder: JsonLdEncoder[JiraError] =
    JsonLdEncoder.computeFromCirce(ContextValue(contexts.error))

  implicit val jiraErrorHttpResponseFields: HttpResponseFields[JiraError] =
    HttpResponseFields { _ =>
      StatusCodes.BadRequest
    }
}
