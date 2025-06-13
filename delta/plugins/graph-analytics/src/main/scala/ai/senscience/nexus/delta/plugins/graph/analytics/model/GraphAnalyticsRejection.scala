package ai.senscience.nexus.delta.plugins.graph.analytics.model

import akka.http.scaladsl.model.StatusCodes
import ch.epfl.bluebrain.nexus.delta.kernel.error.Rejection
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClassUtils
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.query.ElasticSearchClientError
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.HttpResponseFields
import ch.epfl.bluebrain.nexus.delta.sdk.syntax.*
import io.circe.syntax.*
import io.circe.{Encoder, JsonObject}

/**
  * Enumeration of graph analytics rejection types.
  *
  * @param reason
  *   a descriptive message as to why the rejection occurred
  */
sealed abstract class GraphAnalyticsRejection(val reason: String) extends Rejection

object GraphAnalyticsRejection {

  /**
    * Rejection returned when interacting with the elasticsearch views API.
    */
  final case class WrappedElasticSearchRejection(error: ElasticSearchClientError)
      extends GraphAnalyticsRejection(error.reason)

  /**
    * Rejection returned when attempting to interact with graph analytics while providing a property type that cannot be
    * resolved to an Iri.
    *
    * @param id
    *   the property type
    */
  final case class InvalidPropertyType(id: String)
      extends GraphAnalyticsRejection(s"Property type '$id' cannot be expanded to an Iri.")

  implicit val graphAnalyticsRejectionEncoder: Encoder.AsObject[GraphAnalyticsRejection] =
    Encoder.AsObject.instance { r =>
      val tpe = ClassUtils.simpleName(r)
      val obj = JsonObject.empty.add(keywords.tpe, tpe.asJson).add("reason", r.reason.asJson)
      r match {
        case WrappedElasticSearchRejection(rejection) => rejection.asJsonObject
        case _                                        => obj
      }
    }

  implicit final val graphAnalyticsRejectionJsonLdEncoder: JsonLdEncoder[GraphAnalyticsRejection] =
    JsonLdEncoder.computeFromCirce(ContextValue(contexts.error))

  implicit val graphAnalyticsRejectionHttpResponseFields: HttpResponseFields[GraphAnalyticsRejection] =
    HttpResponseFields {
      case WrappedElasticSearchRejection(error) => error.status
      case _                                    => StatusCodes.BadRequest
    }
}
