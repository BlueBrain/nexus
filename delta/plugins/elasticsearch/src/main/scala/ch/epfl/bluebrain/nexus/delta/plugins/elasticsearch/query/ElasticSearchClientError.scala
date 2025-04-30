package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.query

import akka.http.scaladsl.model.{StatusCode as AkkaStatusCode, StatusCodes}
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.error.Rejection
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClassUtils
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.HttpResponseFields
import io.circe.syntax.KeyOps
import io.circe.{Encoder, Json, JsonObject}
import org.http4s.circe.*
import org.http4s.{EntityDecoder, Response, Status}

/**
  * Enumeration of errors raised while querying the Elasticsearch indices
  */
sealed abstract class ElasticSearchClientError(val reason: String, val body: Option[Json]) extends Rejection

object ElasticSearchClientError {

  final case class ElasticsearchActionError(status: Status, action: String)
      extends ElasticSearchClientError(
        s"The elasticsearch $action failed with status $status",
        None
      )

  final case class ElasticsearchCreateIndexError(status: Status, override val body: Option[Json])
      extends ElasticSearchClientError(
        s"The elasticsearch endpoint responded with a status: $status",
        body
      )

  object ElasticsearchCreateIndexError {
    def apply(response: Response[IO]): IO[ElasticsearchCreateIndexError] =
      decodeBody(response).map { body =>
        ElasticsearchCreateIndexError(response.status, Some(body))
      }
  }

  final case class ElasticsearchQueryError(status: Status, override val body: Option[Json])
      extends ElasticSearchClientError(
        s"The elasticsearch endpoint responded with a status: $status",
        body
      )

  object ElasticsearchQueryError {
    def apply(response: Response[IO]): IO[ElasticsearchQueryError] =
      decodeBody(response).map { body =>
        ElasticsearchQueryError(response.status, Some(body))
      }
  }

  final case class ElasticsearchWriteError(status: Status, override val body: Option[Json])
      extends ElasticSearchClientError(
        s"The elasticsearch endpoint responded with a status: $status",
        body
      )

  object ElasticsearchWriteError {
    def apply(response: Response[IO]): IO[ElasticsearchWriteError] =
      decodeBody(response).map { body =>
        ElasticsearchWriteError(response.status, Some(body))
      }
  }

  final case class ScriptCreationDismissed(status: Status, override val body: Option[Json])
      extends ElasticSearchClientError(
        s"The script creation failed with a status: $status",
        body
      )

  object ScriptCreationDismissed {
    def apply(response: Response[IO]): IO[ScriptCreationDismissed] =
      decodeBody(response).map { body =>
        ScriptCreationDismissed(response.status, Some(body))
      }
  }

  /**
    * Rejection returned when attempting to interact with a resource providing an id that cannot be resolved to an Iri.
    *
    * @param id
    *   the resource identifier
    */
  final case class InvalidResourceId(id: String)
      extends ElasticSearchClientError(s"Resource identifier '$id' cannot be expanded to an Iri.", None)

  private def decodeBody(response: Response[IO]): IO[Json] =
    EntityDecoder[IO, Json]
      .decode(response, strict = false)
      .rethrowT

  implicit val elasticSearchQueryErrorEncoder: Encoder.AsObject[ElasticSearchClientError] =
    Encoder.AsObject.instance { r =>
      JsonObject(keywords.tpe := ClassUtils.simpleName(r), "reason" := r.reason)
    }

  implicit final val viewRejectionJsonLdEncoder: JsonLdEncoder[ElasticSearchClientError] =
    JsonLdEncoder.computeFromCirce(ContextValue(Vocabulary.contexts.error))

  implicit val elasticSearchViewRejectionHttpResponseFields: HttpResponseFields[ElasticSearchClientError] =
    HttpResponseFields {
      case ElasticsearchActionError(status, _)      => AkkaStatusCode.int2StatusCode(status.code)
      case ElasticsearchCreateIndexError(status, _) => AkkaStatusCode.int2StatusCode(status.code)
      case ElasticsearchQueryError(status, _)       => AkkaStatusCode.int2StatusCode(status.code)
      case ElasticsearchWriteError(status, _)       => AkkaStatusCode.int2StatusCode(status.code)
      case InvalidResourceId(_)                     => StatusCodes.BadRequest
      case ScriptCreationDismissed(_, _)            => StatusCodes.InternalServerError
    }

}
