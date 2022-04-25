package ch.epfl.bluebrain.nexus.delta.plugins.jira.model

import ch.epfl.bluebrain.nexus.delta.plugins.jira.JiraError
import ch.epfl.bluebrain.nexus.delta.plugins.jira.JiraError.UnknownError
import com.google.api.client.http.HttpRequest
import io.circe.{parser, Json}
import monix.bio.{IO, Task}

final case class JiraResponse(code: Int, response: Option[Json])

object JiraResponse {

  def apply(request: HttpRequest): IO[JiraError, JiraResponse] = {
    Task
      .delay(
        request.execute()
      )
      .flatMap { response =>
        val s = response.parseAsString()
        if (s.nonEmpty) {
          Task.fromEither(parser.parse(s).map { json =>
            JiraResponse(response.getStatusCode, Some(json))
          })
        } else {
          Task.pure(JiraResponse(response.getStatusCode, None))
        }

      }
      .mapError { e => UnknownError(e.getMessage) }
  }
}
