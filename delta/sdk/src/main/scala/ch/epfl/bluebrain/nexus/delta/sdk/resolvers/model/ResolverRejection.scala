package ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model

import akka.http.scaladsl.model.StatusCodes
import ch.epfl.bluebrain.nexus.delta.kernel.Mapper
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClassUtils
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.RdfError
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoderError
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.JsonLdRejection
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.JsonLdRejection.UnexpectedId
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.HttpResponseFields
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContext.ContextRejection
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model.ResourceResolutionReport.ResolverReport
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ResourceRef.{Latest, Revision, Tag}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Identity, ProjectRef, ResourceRef}
import io.circe.syntax._
import io.circe.{Encoder, JsonObject}

/**
  * Enumeration of Resolver rejection types.
  *
  * @param reason
  *   a descriptive message as to why the rejection occurred
  */
sealed abstract class ResolverRejection(val reason: String) extends Product with Serializable

object ResolverRejection {

  /**
    * Rejection returned when a subject intends to retrieve a resolver at a specific revision, but the provided revision
    * does not exist.
    *
    * @param provided
    *   the provided revision
    * @param current
    *   the last known revision
    */
  final case class RevisionNotFound(provided: Int, current: Int)
      extends ResolverRejection(s"Revision requested '$provided' not found, last known revision is '$current'.")

  /**
    * Rejection returned when a subject intends to retrieve a resolver at a specific tag, but the provided tag does not
    * exist.
    *
    * @param tag
    *   the provided tag
    */
  final case class TagNotFound(tag: UserTag) extends ResolverRejection(s"Tag requested '$tag' not found.")

  /**
    * Rejection returned when attempting to create a resolver but the id already exists.
    *
    * @param id
    *   the resource identifier
    * @param project
    *   the project it belongs to
    */
  final case class ResourceAlreadyExists(id: Iri, project: ProjectRef)
      extends ResolverRejection(s"Resource '$id' already exists in project '$project'.")

  /**
    * Rejection returned when attempting to update a resolver with an id that doesn't exist.
    *
    * @param id
    *   the resolver identifier
    * @param project
    *   the project it belongs to
    */
  final case class ResolverNotFound(id: Iri, project: ProjectRef)
      extends ResolverRejection(s"Resolver '$id' not found in project '$project'.")

  /**
    * Rejection returned when attempting to create a resolver where the passed id does not match the id on the payload.
    *
    * @param id
    *   the resolver identifier
    * @param payloadId
    *   the resolver identifier on the payload
    */
  final case class UnexpectedResolverId(id: Iri, payloadId: Iri)
      extends ResolverRejection(s"Resolver '$id' does not match resolver id on payload '$payloadId'.")

  /**
    * Rejection returned when attempting to interact with a resolver providing an id that cannot be resolved to an Iri.
    *
    * @param id
    *   the resolver identifier
    */
  final case class InvalidResolverId(id: String)
      extends ResolverRejection(s"Resolver identifier '$id' cannot be expanded to an Iri.")

  /**
    * Rejection returned when attempting to resolve a resource providing an id that cannot be resolved to an Iri.
    *
    * @param id
    *   the resolver identifier
    */
  final case class InvalidResolvedResourceId(id: String)
      extends ResolverRejection(s"Resource identifier '$id' cannot be expanded to an Iri.")

  /**
    * Rejection when attempting to decode an expanded JsonLD as a case class
    * @param error
    *   the decoder error
    */
  final case class DecodingFailed(error: JsonLdDecoderError) extends ResolverRejection(error.getMessage)

  /**
    * Signals an error converting the source Json to JsonLD
    */
  final case class InvalidJsonLdFormat(id: Option[Iri], rdfError: RdfError)
      extends ResolverRejection(s"Resolver${id.fold("")(id => s" '$id'")} has invalid JSON-LD payload.")

  /**
    * Signals an error when there is another resolver with the provided priority already existing
    */
  final case class PriorityAlreadyExists(project: ProjectRef, id: Iri, priority: Priority)
      extends ResolverRejection(s"Resolver '$id' in project '$project' already has the provided priority '$priority'.")

  /**
    * Rejection returned when attempting to create a resolver with an id that already exists.
    *
    * @param id
    *   the resolver identifier
    */
  final case class DifferentResolverType(id: Iri, found: ResolverType, expected: ResolverType)
      extends ResolverRejection(s"Resolver '$id' is of type '$expected' and can't be updated to be a '$found' .")

  /**
    * Rejection returned when no identities has been provided
    */
  final case object NoIdentities extends ResolverRejection(s"At least one identity of the caller must be provided.")

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
    * @param provided
    *   the provided revision
    * @param expected
    *   the expected revision
    */
  final case class IncorrectRev(provided: Int, expected: Int)
      extends ResolverRejection(
        s"Incorrect revision '$provided' provided, expected '$expected', the resolver may have been updated since last seen."
      )

  private def formatResourceRef(resourceRef: ResourceRef) =
    resourceRef match {
      case Latest(iri)           => s"'$iri' (latest)"
      case Revision(_, iri, rev) => s"'$iri' (revision: $rev)"
      case Tag(_, iri, tag)      => s"'$iri' (tag: $tag)"
    }

  /**
    * Rejection returned when attempting to resolve a resourceId as a data resource or as schema using all resolvers of
    * the given project
    * @param resourceRef
    *   the resource reference to resolve
    * @param projectRef
    *   the project where we want to resolve from
    * @param report
    *   the report for the resolution attempt
    */
  final case class InvalidResolution(
      resourceRef: ResourceRef,
      projectRef: ProjectRef,
      report: ResourceResolutionReport
  ) extends ResolverRejection(
        s"Failed to resolve ${formatResourceRef(resourceRef)} using resolvers of project '$projectRef'."
      )

  /**
    * Rejection returned when attempting to resolve a resourceId as a data resource or as schema using the specified
    * resolver id
    * @param resourceRef
    *   the resource reference to resolve
    * @param resolverId
    *   the id of the resolver
    * @param projectRef
    *   the project where we want to resolve from
    * @param report
    *   the report for resolution attempt
    */
  final case class InvalidResolverResolution(
      resourceRef: ResourceRef,
      resolverId: Iri,
      projectRef: ProjectRef,
      report: ResolverReport
  ) extends ResolverRejection(
        s"Failed to resolve ${formatResourceRef(resourceRef)} using resolver '$resolverId' of project '$projectRef'."
      )

  /**
    * Rejection returned when attempting to update/deprecate a resolver that is already deprecated.
    *
    * @param id
    *   the resolver identifier
    */
  final case class ResolverIsDeprecated(id: Iri) extends ResolverRejection(s"Resolver '$id' is deprecated.")

  /**
    * Signals a rejection caused when interacting with other APIs when fetching a resource
    */
  final case class ProjectContextRejection(rejection: ContextRejection)
      extends ResolverRejection("Something went wrong while interacting with another module.")

  implicit val jsonLdRejectionMapper: Mapper[JsonLdRejection, ResolverRejection] = {
    case UnexpectedId(id, payloadIri)                      => UnexpectedResolverId(id, payloadIri)
    case JsonLdRejection.InvalidJsonLdFormat(id, rdfError) => InvalidJsonLdFormat(id, rdfError)
    case JsonLdRejection.DecodingFailed(error)             => DecodingFailed(error)
  }

  implicit val resolverRejectionEncoder: Encoder.AsObject[ResolverRejection] =
    Encoder.AsObject.instance { r =>
      val tpe = ClassUtils.simpleName(r)
      val obj = JsonObject.empty.add(keywords.tpe, tpe.asJson).add("reason", r.reason.asJson)
      r match {
        case ProjectContextRejection(rejection)         => rejection.asJsonObject
        case InvalidJsonLdFormat(_, rdf)                => obj.add("details", rdf.asJson)
        case IncorrectRev(provided, expected)           => obj.add("provided", provided.asJson).add("expected", expected.asJson)
        case InvalidResolution(_, _, report)            => obj.add("report", report.asJson)
        case InvalidResolverResolution(_, _, _, report) => obj.add("report", report.asJson)
        case _: ResolverNotFound                        => obj.add(keywords.tpe, "ResourceNotFound".asJson)
        case _                                          => obj
      }
    }

  implicit final val resourceRejectionJsonLdEncoder: JsonLdEncoder[ResolverRejection] =
    JsonLdEncoder.computeFromCirce(ContextValue(contexts.error))

  implicit val responseFieldsResolvers: HttpResponseFields[ResolverRejection] =
    HttpResponseFields {
      case RevisionNotFound(_, _)                => StatusCodes.NotFound
      case ResolverNotFound(_, _)                => StatusCodes.NotFound
      case TagNotFound(_)                        => StatusCodes.NotFound
      case InvalidResolution(_, _, _)            => StatusCodes.NotFound
      case InvalidResolverResolution(_, _, _, _) => StatusCodes.NotFound
      case ProjectContextRejection(rej)          => rej.status
      case ResourceAlreadyExists(_, _)           => StatusCodes.Conflict
      case IncorrectRev(_, _)                    => StatusCodes.Conflict
      case _                                     => StatusCodes.BadRequest
    }
}
