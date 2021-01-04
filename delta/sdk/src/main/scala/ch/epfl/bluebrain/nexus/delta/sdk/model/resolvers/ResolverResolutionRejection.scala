package ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers

import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClassUtils
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.Mapper
import ch.epfl.bluebrain.nexus.delta.sdk.model.TagLabel
import ch.epfl.bluebrain.nexus.delta.sdk.model.organizations.OrganizationRejection
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{ProjectRef, ProjectRejection}
import ch.epfl.bluebrain.nexus.delta.sdk.model.resources.ResourceRejection
import ch.epfl.bluebrain.nexus.delta.sdk.model.resources.ResourceRejection.ResourceFetchRejection
import ch.epfl.bluebrain.nexus.delta.sdk.model.schemas.SchemaRejection
import ch.epfl.bluebrain.nexus.delta.sdk.model.schemas.SchemaRejection.SchemaFetchRejection
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
    * Rejection when the access to the given project has been denied by using the identity resolution
    * defined in the resolver
    * @param projectRef          the project where we attempted the resolution
    * @param identityResolution  the identity resolution used
    */
  final case class ProjectAccessDenied(projectRef: ProjectRef, identityResolution: IdentityResolution)
      extends ResolverResolutionRejection(
        s"The supplied authentication is not authorized to access the project '$projectRef' using resolution $identityResolution"
      )

  /**
    * Rejection returned when a resource has been found but has been filtered out because its types didn't match
    * those declared in a cross-project resolver
    * @param types the resource type
    */
  final case class ResourceTypesDenied(projectRef: ProjectRef, types: Set[Iri])
      extends ResolverResolutionRejection(
        s"None of the provided types '${types.mkString(",")}' matched the resolver authorized types"
      )

  /**
    * Rejection when fetching the resource from the project failed
    * @param reason a descriptive message as to why the rejection occurred
    */
  sealed abstract class ResolutionFetchRejection(reason: String) extends ResolverResolutionRejection(reason)

  /**
    * Rejection returned when a subject intends to retrieve a resource using a resolver at a specific revision,
    * but the provided revision does not exist.
    * @param current  the last known revision
    */
  final case class RevisionNotFound(provided: Long, current: Long)
      extends ResolutionFetchRejection(
        s"Revision requested '$provided' not found, last known revision is '$current'."
      )

  /**
    * Rejection returned when a subject intends to retrieve a resource using a resolver at a specific tag,
    * but the provided tag does not exist.
    * @param tag the provided tag
    */
  final case class TagNotFound(tag: TagLabel) extends ResolutionFetchRejection(s"Tag requested '$tag' not found.")

  /**
    * Rejection returned when attempting to fetch a resource using a resolver that doesn't exist.
    * @param id             the iri of the resource
    * @param projectRef      the project it belongs to
    */
  final case class ResourceNotFound(id: Iri, projectRef: ProjectRef)
      extends ResolutionFetchRejection(s"The resource was not found in project '$projectRef'.")

  /**
    * Rejection returned when attempting to interact with a resource providing an id that cannot be resolved to an Iri.
    *
    * @param id the resource identifier
    */
  final case class InvalidId(id: String)
      extends ResolutionFetchRejection(s"The identifier '$id' cannot be expanded to an Iri.")

  /**
    * Rejection returned when attempting to fetch a deprecated resource when it is not allowed.
    *
    * @param id the schema identifier
    */
  final case class ResourceIsDeprecated(id: Iri)
      extends ResolutionFetchRejection(s"Resource '$id' is deprecated and can't be fetched in this context.")

  /**
    * Rejection the rejection when attempting to resolve with an invalid resolver
    * (i.e deprecated, not found, invalid resolver identifier)
    *
    * @param rejection the resolver rejection
    */
  final case class WrappedResolverRejection(rejection: ResolverRejection)
      extends ResolutionFetchRejection(rejection.reason)

  /**
    * Rejection returned when attempting to fetch a resource from a non existing project.
    *
    * @param rejection the project rejection
    */
  final case class WrappedProjectRejection(rejection: ProjectRejection)
      extends ResolutionFetchRejection(rejection.reason)

  /**
    * Signals a rejection caused when interacting with the organizations API
    */
  final case class WrappedOrganizationRejection(rejection: OrganizationRejection)
      extends ResolutionFetchRejection(rejection.reason)

  implicit private[model] val resolverResolutionRejectionEncoder: Encoder.AsObject[ResolverResolutionRejection] =
    Encoder.AsObject.instance { r =>
      val tpe = ClassUtils.simpleName(r)
      val obj = JsonObject(keywords.tpe -> tpe.asJson, "reason" -> r.reason.asJson)
      r match {
        case WrappedOrganizationRejection(rejection) => rejection.asJsonObject
        case WrappedProjectRejection(rejection)      => rejection.asJsonObject
        case WrappedResolverRejection(rejection)     => rejection.asJsonObject
        case RevisionNotFound(provided, expected)    =>
          obj.add("provided", provided.asJson).add("expected", expected.asJson)
        case _                                       => obj
      }
    }

  implicit final val resolverResolutionRejectionJsonLdEncoder: JsonLdEncoder[ResolverResolutionRejection] =
    JsonLdEncoder.computeFromCirce(ContextValue(contexts.error))

  implicit val schemaResolutionRejectionMapper: Mapper[SchemaFetchRejection, ResolutionFetchRejection] = {
    case SchemaRejection.InvalidSchemaId(id)                        => InvalidId(id)
    case SchemaRejection.SchemaNotFound(id, project)                => ResourceNotFound(id, project)
    case SchemaRejection.SchemaIsDeprecated(id)                     => ResourceIsDeprecated(id)
    case SchemaRejection.RevisionNotFound(provided, current)        => RevisionNotFound(provided, current)
    case SchemaRejection.TagNotFound(label)                         => TagNotFound(label)
    case SchemaRejection.WrappedProjectRejection(projectRejection)  => WrappedProjectRejection(projectRejection)
    case SchemaRejection.WrappedOrganizationRejection(orgRejection) => WrappedOrganizationRejection(orgRejection)
  }

  implicit val resourceResolutionRejectionMapper: Mapper[ResourceFetchRejection, ResolutionFetchRejection] = {
    case ResourceRejection.InvalidResourceId(id)                      => ResolverResolutionRejection.InvalidId(id)
    case ResourceRejection.ResourceNotFound(id, project, _)           => ResolverResolutionRejection.ResourceNotFound(id, project)
    case ResourceRejection.RevisionNotFound(provided, current)        =>
      ResolverResolutionRejection.RevisionNotFound(provided, current)
    case ResourceRejection.TagNotFound(label)                         => ResolverResolutionRejection.TagNotFound(label)
    case ResourceRejection.WrappedProjectRejection(projectRejection)  =>
      ResolverResolutionRejection.WrappedProjectRejection(projectRejection)
    case ResourceRejection.WrappedOrganizationRejection(orgRejection) =>
      ResolverResolutionRejection.WrappedOrganizationRejection(orgRejection)
  }
}
