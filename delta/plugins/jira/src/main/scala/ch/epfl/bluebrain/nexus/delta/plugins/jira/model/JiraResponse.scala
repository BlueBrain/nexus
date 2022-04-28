package ch.epfl.bluebrain.nexus.delta.plugins.jira.model

import ch.epfl.bluebrain.nexus.delta.plugins.jira.JiraError
import com.google.api.client.http.HttpRequest
import io.circe.{parser, Json}
import monix.bio.{IO, Task}

/**
  * Jira response
  */
final case class JiraResponse(content: Option[Json])

object JiraResponse {

  def apply(request: HttpRequest): IO[JiraError, JiraResponse] = {
    Task
      .delay(
        request.execute()
      )
      .flatMap { response =>
        val content = response.parseAsString()
        if (content.nonEmpty) {
          Task.fromEither(parser.parse(content)).map { r => JiraResponse(Some(r)) }
        } else {
          Task.pure(JiraResponse(None))
        }
      }
      .mapError { JiraError.from }
  }
}
