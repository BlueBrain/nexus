package ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers

import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClassUtils
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.RdfError
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoderError
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.Mapper
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.JsonLdRejection
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.JsonLdRejection.UnexpectedId
import ch.epfl.bluebrain.nexus.delta.sdk.model.{ResourceType, TagLabel}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity
import ch.epfl.bluebrain.nexus.delta.sdk.model.organizations.OrganizationRejection
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{ProjectRef, ProjectRejection}
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.ResourceResolutionReport.ResolverReport
import io.circe.syntax._
import io.circe.{Encoder, JsonObject}

/**
  * Enumeration of Resolver rejection types.
  *
  * @param reason a descriptive message as to why the rejection occurred
  */
sealed abstract class ResolverRejection(val reason: String) extends Product with Serializable

object ResolverRejection {

  /**
    * Rejection returned when a subject intends to retrieve a resolver at a specific revision, but the provided revision
    * does not exist.
    *
    * @param provided the provided revision
    * @param current  the last known revision
    */
  final case class RevisionNotFound(provided: Long, current: Long)
      extends ResolverRejection(s"Revision requested '$provided' not found, last known revision is '$current'.")

  /**
    * Rejection returned when a subject intends to retrieve a resolver at a specific tag, but the provided tag
    * does not exist.
    *
    * @param tag the provided tag
    */
  final case class TagNotFound(tag: TagLabel) extends ResolverRejection(s"Tag requested '$tag' not found.")

  /**
    * Rejection returned when attempting to create a resolver with an id that already exists.
    *
    * @param id      the resolver identifier
    * @param project the project it belongs to
    */
  final case class ResolverAlreadyExists(id: Iri, project: ProjectRef)
      extends ResolverRejection(s"Resolver '$id' already exists in project '$project'.")

  /**
    * Rejection returned when attempting to update a resolver with an id that doesn't exist.
    *
    * @param id      the resolver identifier
    * @param project the project it belongs to
    */
  final case class ResolverNotFound(id: Iri, project: ProjectRef)
      extends ResolverRejection(s"Resolver '$id' not found in project '$project'.")

  /**
    * Rejection returned when attempting to create a resolver where the passed id does not match the id on the payload.
    *
    * @param id        the resolver identifier
    * @param payloadId the resolver identifier on the payload
    */
  final case class UnexpectedResolverId(id: Iri, payloadId: Iri)
      extends ResolverRejection(s"Resolver '$id' does not match resolver id on payload '$payloadId'.")

  /**
    * Rejection returned when attempting to interact with a resolver providing an id that cannot be resolved to an Iri.
    *
    * @param id        the resolver identifier
    */
  final case class InvalidResolverId(id: String)
      extends ResolverRejection(s"Resolver identifier '$id' cannot be expanded to an Iri.")

  /**
    * Rejection returned when attempting to resolve a resource providing an id that cannot be resolved to an Iri.
    *
    * @param id        the resolver identifier
    */
  final case class InvalidResolvedResourceId(id: String)
      extends ResolverRejection(s"Resource identifier '$id' cannot be expanded to an Iri.")

  /**
    * Rejection when attempting to decode an expanded JsonLD as a case class
    * @param error the decoder error
    */
  final case class DecodingFailed(error: JsonLdDecoderError) extends ResolverRejection(error.getMessage)

  /**
    * Signals an error converting the source Json to JsonLD
    */
  final case class InvalidJsonLdFormat(id: Option[Iri], rdfError: RdfError)
      extends ResolverRejection(s"Resolver ${id.fold("")(id => s"'$id'")} has invalid JSON-LD payload.")

  /**
    * Rejection returned when attempting to create a resolver with an id that already exists.
    *
    * @param id the resolver identifier
    */
  final case class DifferentResolverType(id: Iri, found: ResolverType, expected: ResolverType)
      extends ResolverRejection(s"Resolver '$id' is of type '$found' and can't be updated to be a '$expected' .")

  /**
    * Rejection returned when no identities has been provided
    */
  final case object NoIdentities extends ResolverRejection(s"At least one identity of the caller must be provided")

  /**
    * Rejection return when the logged caller does not have one of the provided identities
    */
  final case class InvalidIdentities(missingIdentities: Set[Identity])
      extends ResolverRejection(
        s"The caller doesn't have some of the provided identities: ${missingIdentities.mkString(",")}"
      )

  /**
    * Rejection returned when a subject intends to perform an operation on the current resolver, but either provided an
    * incorrect revision or a concurrent update won over this attempt.
    *
    * @param provided the provided revision
    * @param expected the expected revision
    */
  final case class IncorrectRev(provided: Long, expected: Long)
      extends ResolverRejection(
        s"Incorrect revision '$provided' provided, expected '$expected', the resolver may have been updated since last seen."
      )

  /**
    * Rejection returned when attempting to resolve a resourceId as a data resource or as schema
    * using all resolvers of the given project
    * @param resourceId      the id of the resource to resolve
    * @param projectRef      the project where we want to resolve from
    * @param reports         the report for resolution attempts for each resource type
    */
  final case class InvalidResolution(
      resourceId: Iri,
      projectRef: ProjectRef,
      reports: Map[ResourceType, ResourceResolutionReport]
  ) extends ResolverRejection(
        s"Failed to resolve $resourceId as a data resource and as a schema using resolvers of project $projectRef"
      )

  /**
    * Rejection returned when attempting to resolve a resourceId as a data resource or as schema
    * using the specified resolver id
    * @param resourceId      the id of the resource to resolve
    * @param resolverId      the id of the resolver
    * @param projectRef      the project where we want to resolve from
    * @param reports         the report for resolution attempts for each resource type
    */
  final case class InvalidResolverResolution(
      resourceId: Iri,
      resolverId: Iri,
      projectRef: ProjectRef,
      reports: Map[ResourceType, ResolverReport]
  ) extends ResolverRejection(
        s"Failed to resolve $resourceId as a data resource and as a schema using resolvers of project $projectRef"
      )

  /**
    * Rejection returned when attempting to update/deprecate a resolver that is already deprecated.
    *
    * @param id the resolver identifier
    */
  final case class ResolverIsDeprecated(id: Iri) extends ResolverRejection(s"Resolver '$id' is deprecated.")

  /**
    * Rejection returned when the associated project is invalid
    *
    * @param rejection the rejection which occurred with the project
    */
  final case class WrappedProjectRejection(rejection: ProjectRejection) extends ResolverRejection(rejection.reason)

  /**
    * Rejection returned when the associated organization is invalid
    *
    * @param rejection the rejection which occurred with the organization
    */
  final case class WrappedOrganizationRejection(rejection: OrganizationRejection)
      extends ResolverRejection(rejection.reason)

  /**
    * Rejection returned when the returned state is the initial state after a Resolvers.evaluation plus a Resolvers.next
    * Note: This should never happen since the evaluation method already guarantees that the next function returns a current
    */
  final case class UnexpectedInitialState(id: Iri, project: ProjectRef)
      extends ResolverRejection(s"Unexpected initial state for resolver '$id' of project '$project'.")

  implicit val jsonLdRejectionMapper: Mapper[JsonLdRejection, ResolverRejection] = {
    case UnexpectedId(id, payloadIri)                      => UnexpectedResolverId(id, payloadIri)
    case JsonLdRejection.InvalidJsonLdFormat(id, rdfError) => InvalidJsonLdFormat(id, rdfError)
    case JsonLdRejection.DecodingFailed(error)             => DecodingFailed(error)
  }

  implicit val projectRejectionMapper: Mapper[ProjectRejection, ResolverRejection] = {
    case ProjectRejection.WrappedOrganizationRejection(r) => WrappedOrganizationRejection(r)
    case value                                            => WrappedProjectRejection(value)
  }

  implicit val resolverRejectionEncoder: Encoder.AsObject[ResolverRejection] =
    Encoder.AsObject.instance { r =>
      val tpe = ClassUtils.simpleName(r)
      val obj = JsonObject.empty.add(keywords.tpe, tpe.asJson).add("reason", r.reason.asJson)
      r match {
        case WrappedOrganizationRejection(rejection)     => rejection.asJsonObject
        case WrappedProjectRejection(rejection)          => rejection.asJsonObject
        case InvalidJsonLdFormat(_, details)             => obj.add("details", details.reason.asJson)
        case IncorrectRev(provided, expected)            => obj.add("provided", provided.asJson).add("expected", expected.asJson)
        case InvalidResolution(_, _, reports)            =>
          obj.add(
            "reports",
            JsonObject
              .fromMap(
                reports.map { case (resourceType, report) =>
                  resourceType.name.value.toLowerCase -> report.asJson
                }
              )
              .asJson
          )
        case InvalidResolverResolution(_, _, _, reports) =>
          obj.add(
            "reports",
            JsonObject
              .fromMap(
                reports.map { case (resourceType, report) =>
                  resourceType.name.value.toLowerCase -> report.asJson
                }
              )
              .asJson
          )
        case _                                           => obj
      }
    }

  implicit final val resourceRejectionJsonLdEncoder: JsonLdEncoder[ResolverRejection] =
    JsonLdEncoder.computeFromCirce(ContextValue(contexts.error))

}
