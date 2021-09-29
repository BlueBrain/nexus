package ch.epfl.bluebrain.nexus.delta.plugins.graph.analytics.model

import akka.http.scaladsl.model.StatusCodes
import ch.epfl.bluebrain.nexus.delta.kernel.Mapper
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClassUtils
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewRejection
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.HttpResponseFields
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRejection
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import io.circe.syntax._
import io.circe.{Encoder, JsonObject}

/**
  * Enumeration of graph analytics rejection types.
  *
  * @param reason
  *   a descriptive message as to why the rejection occurred
  */
sealed abstract class GraphAnalyticsRejection(val reason: String) extends Product with Serializable

object GraphAnalyticsRejection {

  /**
    * Rejection returned when interacting with the elasticsearch views API.
    */
  final case class WrappedElasticSearchRejection(rejection: ElasticSearchViewRejection)
      extends GraphAnalyticsRejection(rejection.reason)

  /**
    * Rejection returned when attempting to interact with graph analytics while providing a property type that cannot be
    * resolved to an Iri.
    *
    * @param id
    *   the property type
    */
  final case class InvalidPropertyType(id: String)
      extends GraphAnalyticsRejection(s"Property type '$id' cannot be expanded to an Iri.")

  /**
    * Signals a rejection caused when interacting with the projects API
    */
  final case class WrappedProjectRejection(rejection: ProjectRejection)
      extends GraphAnalyticsRejection(rejection.reason)

  implicit val graphAnalyticsRejectionEncoder: Encoder.AsObject[GraphAnalyticsRejection] =
    Encoder.AsObject.instance { r =>
      val tpe = ClassUtils.simpleName(r)
      val obj = JsonObject.empty.add(keywords.tpe, tpe.asJson).add("reason", r.reason.asJson)
      r match {
        case WrappedElasticSearchRejection(rejection) => rejection.asJsonObject
        case WrappedProjectRejection(rejection)       => rejection.asJsonObject
        case _                                        => obj
      }
    }

  implicit final val projectToGraphAnalyticsRejectionMapper: Mapper[ProjectRejection, GraphAnalyticsRejection] =
    WrappedProjectRejection.apply

  implicit final val graphAnalyticsRejectionJsonLdEncoder: JsonLdEncoder[GraphAnalyticsRejection] =
    JsonLdEncoder.computeFromCirce(ContextValue(contexts.error))

  implicit val graphAnalyticsRejectionHttpResponseFields: HttpResponseFields[GraphAnalyticsRejection] =
    HttpResponseFields {
      case WrappedElasticSearchRejection(rej) => rej.status
      case WrappedProjectRejection(rej)       => rej.status
      case _                                  => StatusCodes.BadRequest
    }
}
