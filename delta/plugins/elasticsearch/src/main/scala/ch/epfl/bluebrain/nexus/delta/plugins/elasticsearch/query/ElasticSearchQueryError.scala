package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.query

import akka.http.scaladsl.model.StatusCodes
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClassUtils
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.http.HttpClientError
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.HttpResponseFields
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContext.ContextRejection
import io.circe.syntax.KeyOps
import io.circe.{Encoder, JsonObject}

/**
  * Enumeration of errors raised while querying the Elasticsearch indices
  */
sealed abstract class ElasticSearchQueryError(val reason: String)

object ElasticSearchQueryError {

  /**
    * Signals a rejection caused when interacting with other APIs when fetching a resource
    */
  final case class ProjectContextRejection(rejection: ContextRejection)
      extends ElasticSearchQueryError("Something went wrong while interacting with another module.")

  /**
    * Error returned when interacting with the elasticserch client
    */
  final case class ElasticSearchClientError(error: HttpClientError)
      extends ElasticSearchQueryError("Error while interacting with the underlying ElasticSearch index")

  /**
    * Rejection returned when attempting to interact with a resource providing an id that cannot be resolved to an Iri.
    *
    * @param id
    *   the resource identifier
    */
  final case class InvalidResourceId(id: String)
      extends ElasticSearchQueryError(s"Resource identifier '$id' cannot be expanded to an Iri.")

  implicit val elasticSearchQueryErrorEncoder: Encoder.AsObject[ElasticSearchQueryError] =
    Encoder.AsObject.instance { r =>
      JsonObject(keywords.tpe := ClassUtils.simpleName(r), "reason" := r.reason)
    }

  implicit final val viewRejectionJsonLdEncoder: JsonLdEncoder[ElasticSearchQueryError] =
    JsonLdEncoder.computeFromCirce(ContextValue(Vocabulary.contexts.error))

  implicit val elasticSearchViewRejectionHttpResponseFields: HttpResponseFields[ElasticSearchQueryError] =
    HttpResponseFields {
      case ElasticSearchClientError(error) => error.errorCode.getOrElse(StatusCodes.InternalServerError)
      case InvalidResourceId(_)            => StatusCodes.BadRequest
      case ProjectContextRejection(rej)    => rej.status
    }

}
