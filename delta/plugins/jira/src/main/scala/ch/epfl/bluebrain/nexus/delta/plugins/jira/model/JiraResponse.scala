package ch.epfl.bluebrain.nexus.delta.plugins.jira.model

import cats.effect.IO
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.plugins.jira.JiraError
import com.google.api.client.http.HttpRequest
import io.circe.{parser, Json}

/**
  * Jira response
  */
final case class JiraResponse(content: Option[Json])

object JiraResponse {

  def apply(request: HttpRequest): IO[JiraResponse] = {
    IO.blocking(request.execute())
      .flatMap { response =>
        val content = response.parseAsString()
        if (content.nonEmpty) {
          IO.fromEither(parser.parse(content)).map { r => JiraResponse(Some(r)) }
        } else {
          IO.pure(JiraResponse(None))
        }
      }
      .adaptError { e => JiraError.from(e) }
  }
}
