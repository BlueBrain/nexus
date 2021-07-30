package ch.epfl.bluebrain.nexus.delta.plugins.search.model

import akka.http.scaladsl.model.StatusCodes
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClassUtils
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.http.HttpClientError
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.HttpResponseFields
import io.circe.syntax._
import io.circe.{Encoder, JsonObject}

/**
  * Enumeration of search rejection types.
  *
  * @param reason a descriptive message as to why the rejection occurred
  */
sealed abstract class SearchRejection(val reason: String) extends Product with Serializable

object SearchRejection {

  /**
    * Signals a rejection caused when interacting with the elasticserch client
    */
  final case class WrappedElasticSearchClientError(error: HttpClientError)
      extends SearchRejection("Error while interacting with the underlying ElasticSearch index")

  implicit private[plugins] val searchViewRejectionEncoder: Encoder.AsObject[SearchRejection] =
    Encoder.AsObject.instance { r =>
      val tpe = ClassUtils.simpleName(r)
      val obj = JsonObject(keywords.tpe -> tpe.asJson, "reason" -> r.reason.asJson)
      r match {
        case WrappedElasticSearchClientError(rejection) =>
          rejection.jsonBody.flatMap(_.asObject).getOrElse(obj.add(keywords.tpe, "ElasticSearchClientError".asJson))
        case _                                          => obj
      }
    }

  implicit final val searchRejectionJsonLdEncoder: JsonLdEncoder[SearchRejection] =
    JsonLdEncoder.computeFromCirce(ContextValue(Vocabulary.contexts.error))

  implicit val searchHttpResponseFields: HttpResponseFields[SearchRejection] =
    HttpResponseFields {
      case WrappedElasticSearchClientError(error) => error.errorCode.getOrElse(StatusCodes.InternalServerError)
      case _                                      => StatusCodes.BadRequest
    }
}
