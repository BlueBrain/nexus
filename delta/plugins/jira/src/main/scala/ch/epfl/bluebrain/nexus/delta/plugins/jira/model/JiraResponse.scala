package ch.epfl.bluebrain.nexus.delta.plugins.jira.model

import ch.epfl.bluebrain.nexus.delta.plugins.jira.JiraError
import ch.epfl.bluebrain.nexus.delta.plugins.jira.JiraError.UnknownError
import com.google.api.client.http.HttpRequest
import io.circe.{parser, Json}
import monix.bio.{IO, Task}

final case class JiraResponse(code: Int, response: Json)

object JiraResponse {

  def apply(request: HttpRequest): IO[JiraError, JiraResponse] = {
    Task
      .delay(
        request.execute()
      )
      .flatMap { response =>
        Task.fromEither(parser.parse(response.parseAsString()).map(JiraResponse(response.getStatusCode, _)))
      }
      .mapError { e => UnknownError(e.getMessage) }
  }
}
