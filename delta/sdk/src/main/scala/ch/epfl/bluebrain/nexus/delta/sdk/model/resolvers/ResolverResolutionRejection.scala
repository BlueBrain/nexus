package ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers

import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClassUtils
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Label, ResourceType}
import io.circe.syntax._
import io.circe.{Encoder, JsonObject}

/**
  * Enumeration of Resolver resolution rejection types.
  *
  * @param reason a descriptive message as to why the rejection occurred
  */
sealed abstract class ResolverResolutionRejection(val reason: String) extends Product with Serializable

object ResolverResolutionRejection {

  /**
    * Rejection returned when a subject intends to retrieve a resource using a resolver at a specific revision,
    * but the provided revision does not exist.
    *
    * @param provided the provided revision
    * @param current  the last known revision
    */
  final case class RevisionNotFound(provided: Long, current: Long)
      extends ResolverResolutionRejection(
        s"Revision requested '$provided' not found, last known revision is '$current'."
      )

  /**
    * Rejection returned when a subject intends to retrieve a resource using a resolver at a specific tag,
    * but the provided tag does not exist.
    *
    * @param tag the provided tag
    */
  final case class TagNotFound(tag: Label) extends ResolverResolutionRejection(s"Tag requested '$tag' not found.")

  /**
    * Rejection returned when attempting to fetch a resource using a resolver that doesn't exist.
    *
    * @param id           the resource identifier
    * @param project      the project it belongs to
    * @param resourceType the resource type
    */
  final case class ResourceNotFound(id: Iri, project: ProjectRef, resourceType: ResourceType)
      extends ResolverResolutionRejection(s"$resourceType '$id' not found in project '$project'.")

  /**
    * Rejection returned when attempting to fetch a resource from a non existing project.
    *
    * @param projectRef the identifier of the project
    */
  final case class ProjectNotFound(projectRef: ProjectRef)
      extends ResolverResolutionRejection(s"Project '$projectRef' not found.")

  implicit private[model] val resolverResolutionRejectionEncoder: Encoder.AsObject[ResolverResolutionRejection] =
    Encoder.AsObject.instance { r =>
      val tpe = ClassUtils.simpleName(r)
      r match {
        case ResourceNotFound(_, _, tpe) =>
          JsonObject(keywords.tpe -> s"${tpe.name}NotFound".asJson, "reason" -> r.reason.asJson)
        case _                           =>
          JsonObject(keywords.tpe -> tpe.asJson, "reason" -> r.reason.asJson)
      }
    }

  implicit final val resolverResolutionRejectionJsonLdEncoder: JsonLdEncoder[ResolverResolutionRejection] =
    JsonLdEncoder.computeFromCirce(ContextValue(contexts.error))
}
