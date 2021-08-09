package ch.epfl.bluebrain.nexus.delta.sdk.model.quotas

import ch.epfl.bluebrain.nexus.delta.kernel.Mapper
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRejection.ProjectNotFound
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{ProjectRef, ProjectRejection}
import io.circe.syntax._
import io.circe.{Encoder, JsonObject}

/**
  * Enumeration of Quota rejection types.
  *
  * @param reason a descriptive message as to why the rejection occurred
  */
sealed abstract class QuotaRejection(val reason: String) extends Product with Serializable

object QuotaRejection {

  /**
    * Signals a rejection caused when the quotas configuration is disabled
    */
  final case class QuotasDisabled(project: ProjectRef)
      extends QuotaRejection(s"Quotas disabled for project '$project'.")

  /**
    * Signals a rejection caused when interacting with the projects API
    */
  final case class WrappedProjectRejection(rej: ProjectNotFound) extends QuotaRejection(rej.reason)

  implicit val quotaProjectRejectionMapper: Mapper[ProjectNotFound, QuotaRejection] =
    WrappedProjectRejection(_)

  implicit val quotaRejectionEncoder: Encoder.AsObject[QuotaRejection] =
    Encoder.AsObject.instance {
      case r: QuotasDisabled                  => JsonObject(keywords.tpe -> "QuotasDisabled".asJson, "reason" -> r.reason.asJson)
      case WrappedProjectRejection(rejection) => (rejection: ProjectRejection).asJsonObject
    }

  implicit final val quotaRejectionJsonLdEncoder: JsonLdEncoder[QuotaRejection] =
    JsonLdEncoder.computeFromCirce(ContextValue(contexts.error))

}
