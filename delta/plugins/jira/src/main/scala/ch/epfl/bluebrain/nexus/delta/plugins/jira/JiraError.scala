package ch.epfl.bluebrain.nexus.delta.plugins.jira

import akka.http.scaladsl.model.StatusCodes
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClassUtils
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.error.SDKError
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.HttpResponseFields
import com.google.api.client.http.HttpResponseException
import io.circe.syntax.EncoderOps
import io.circe.{parser, Encoder, Json, JsonObject}

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
    * An access token was expected for the current user
    */
  final case object AccessTokenExpected extends JiraError("An access token was expected for the current user.")

  /**
    * An error when interacting with Jira after the authentication process
    */
  final case class JiraResponseError(statusCode: Int, content: Json)
      extends JiraError(s"Jira responded with an error with status code $statusCode")

  /**
    * Unexpected error
    */
  final case class UnexpectedError(reason: String, details: Option[String] = None) extends JiraError(reason, details)

  /**
    * Create a Jira error from the given exception
    */
  def from(e: Throwable): JiraError =
    e match {
      case httpException: HttpResponseException =>
        JiraResponseError(
          httpException.getStatusCode,
          parser.parse(httpException.getContent).getOrElse(Json.fromString(httpException.getContent))
        )
      case _                                    =>
        UnexpectedError(e.getMessage)
    }

  implicit val jiraErrorEncoder: Encoder.AsObject[JiraError] =
    Encoder.AsObject.instance { e =>
      val tpe     = ClassUtils.simpleName(e)
      val default = JsonObject.empty.add(keywords.tpe, tpe.asJson).add("reason", e.getMessage.asJson)
      e match {
        case JiraResponseError(_, content) => default.add("content", content)
        case _                             => default
      }
    }

  implicit final val jiraErrorJsonLdEncoder: JsonLdEncoder[JiraError] =
    JsonLdEncoder.computeFromCirce(ContextValue(contexts.error))

  implicit val jiraErrorHttpResponseFields: HttpResponseFields[JiraError] =
    HttpResponseFields {
      case JiraResponseError(statusCode, _) => StatusCodes.getForKey(statusCode).getOrElse(StatusCodes.BadRequest)
      case _                                => StatusCodes.BadRequest
    }
}
