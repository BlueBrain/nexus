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
  * @param reason
  *   a descriptive message as to why the rejection occurred
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

  sealed abstract class QuotaReached(reason: String) extends QuotaRejection(reason)

  object QuotaReached {

    /**
      * Signals a rejection caused when the resources quota for a project is reached
      */
    final case class QuotaResourcesReached(project: ProjectRef, resources: Int)
        extends QuotaReached(s"Quotas for project '$project' reached. Maximum resources allowed: '$resources'.")

    /**
      * Signals a rejection caused when the events quota for a project is reached
      */
    final case class QuotaEventsReached(project: ProjectRef, events: Int)
        extends QuotaReached(s"Quotas for project '$project' reached. Maximum events allowed: '$events'.")
  }

  implicit val quotaProjectRejectionMapper: Mapper[ProjectNotFound, QuotaRejection] =
    WrappedProjectRejection(_)

  implicit val quotaRejectionEncoder: Encoder.AsObject[QuotaRejection] =
    Encoder.AsObject.instance {
      case r: QuotaReached                    => JsonObject(keywords.tpe -> "QuotaReached".asJson, "reason" -> r.reason.asJson)
      case r: QuotasDisabled                  => JsonObject(keywords.tpe -> "QuotasDisabled".asJson, "reason" -> r.reason.asJson)
      case WrappedProjectRejection(rejection) => (rejection: ProjectRejection).asJsonObject
    }

  implicit final val quotaRejectionJsonLdEncoder: JsonLdEncoder[QuotaRejection] =
    JsonLdEncoder.computeFromCirce(ContextValue(contexts.error))

}
