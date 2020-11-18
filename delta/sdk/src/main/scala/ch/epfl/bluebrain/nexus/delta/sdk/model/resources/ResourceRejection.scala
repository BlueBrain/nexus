package ch.epfl.bluebrain.nexus.delta.sdk.model.resources

import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClassUtils
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.{BNode, Iri}
import ch.epfl.bluebrain.nexus.delta.rdf.RdfError
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.shacl.ValidationReport
import ch.epfl.bluebrain.nexus.delta.sdk.Mapper
import ch.epfl.bluebrain.nexus.delta.sdk.model.organizations.OrganizationRejection
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRejection
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Label, ResourceRef}
import io.circe.syntax._
import io.circe.{Encoder, JsonObject}

/**
  * Enumeration of Resource rejection types.
  *
  * @param reason a descriptive message as to why the rejection occurred
  */
sealed abstract class ResourceRejection(val reason: String) extends Product with Serializable

object ResourceRejection {

  /**
    * Rejection returned when a subject intends to retrieve a resource at a specific revision, but the provided revision
    * does not exist.
    *
    * @param provided the provided revision
    * @param current  the last known revision
    */
  final case class RevisionNotFound(provided: Long, current: Long)
      extends ResourceRejection(s"Revision requested '$provided' not found, last known revision is '$current'.")

  /**
    * Rejection returned when a subject intends to retrieve a resource at a specific tag, but the provided tag
    * does not exist.
    *
    * @param tag the provided tag
    */
  final case class TagNotFound(tag: Label) extends ResourceRejection(s"Tag requested '$tag' not found.")

  /**
    * Rejection returned when attempting to create a resource with an id that already exists.
    *
    * @param id      the resource identifier
    */
  final case class ResourceAlreadyExists(id: Iri) extends ResourceRejection(s"Resource '$id' already exists.")

  /**
    * Rejection returned when attempting to update a resource with an id that doesn't exist.
    *
    * @param id        the resource identifier
    * @param schemaOpt the optional resource schema
    */
  final case class ResourceNotFound(id: Iri, schemaOpt: Option[ResourceRef])
      extends ResourceRejection(s"Resource '$id' not found${schemaOpt.fold("")(schema => s" with schema '$schema'")}.")

  /**
    * Rejection returned when attempting to create a resource where the passed id does not match the id on the payload.
    *
    * @param id        the resource identifier
    * @param payloadId the resource identifier on the payload
    */
  final case class UnexpectedResourceId(id: Iri, payloadId: Iri)
      extends ResourceRejection(s"Resource '$id' does not match resource id on payload '$payloadId'.")

  /**
    * Rejection returned when attempting to interact with a resource providing an id that cannot be resolved to an Iri.
    *
    * @param id        the resource identifier
    */
  final case class InvalidResourceId(id: String)
      extends ResourceRejection(s"Resource identifier '$id' cannot be expanded to an Iri.")

  /**
    * Rejection returned when attempting to create/update a resource where the payload does not satisfy the SHACL schema constrains.
    *
    * @param id      the resource identifier
    * @param schema  the schema for which validation failed
    * @param report  the SHACL validation failure report
    */
  final case class InvalidResource(id: Iri, schema: ResourceRef, report: ValidationReport)
      extends ResourceRejection(s"Resource '$id' failed to validate against the constrains defined in schema '$schema'")

  /**
    * Rejection returned when attempting to update/deprecate a resource with a different schema than the resource schema.
    *
    * @param id       the resource identifier
    * @param provided the resource provided schema
    * @param expected the resource schema
    */
  final case class UnexpectedResourceSchema(id: Iri, provided: ResourceRef, expected: ResourceRef)
      extends ResourceRejection(
        s"Resource '$id' is not constrained by the provided schema '$provided', but by the schema '$expected'."
      )

  /**
    * Rejection returned when attempting to create a SHACL engine.
    *
    * @param id      the resource identifier
    * @param schema  the resource provided schema
    * @param details the SHACL engine errors
    */
  final case class ResourceShaclEngineRejection(id: Iri, schema: ResourceRef, details: String)
      extends ResourceRejection(s"Resource '$id' failed to produce a SHACL engine for schema '$schema'.")

  /**
    * Rejection returned when attempting to update/deprecate a resource that is already deprecated.
    *
    * @param id      the resource identifier
    */
  final case class ResourceIsDeprecated(id: Iri) extends ResourceRejection(s"Resource '$id' is deprecated.")

  /**
    * Rejection returned when a subject intends to perform an operation on the current resource, but either provided an
    * incorrect revision or a concurrent update won over this attempt.
    *
    * @param provided the provided revision
    * @param expected the expected revision
    */
  final case class IncorrectRev(provided: Long, expected: Long)
      extends ResourceRejection(
        s"Incorrect revision '$provided' provided, expected '$expected', the resource may have been updated since last seen."
      )

  /**
    * Signals a rejection caused when interacting with the projects API
    */
  final case class WrappedProjectRejection(rejection: ProjectRejection) extends ResourceRejection(rejection.reason)

  /**
    * Signals a rejection caused when interacting with the organizations API
    */
  final case class WrappedOrganizationRejection(rejection: OrganizationRejection)
      extends ResourceRejection(rejection.reason)

  /**
    * Rejection returned when attempting to validate a resource against a schema that is deprecated.
    *
    * @param ref     the schema reference
    */
  final case class SchemaIsDeprecated(ref: ResourceRef) extends ResourceRejection(s"Schema '$ref' is deprecated.")

  /**
    * Rejection returned when attempting to validate a resource against a schema that is deprecated.
    *
    * @param ref     the schema reference
    */
  final case class SchemaNotFound(ref: ResourceRef) extends ResourceRejection(s"Schema '$ref' not found.")

  /**
    * Signals an error converting the source Json to JsonLD
    */
  final case class InvalidJsonLdFormat(idOpt: Option[Iri], rdfError: RdfError)
      extends ResourceRejection(s"Resource ${idOpt.fold("")(id => s"'$id'")} has invalid JSON-LD payload.")

  /**
    * Rejection returned when the returned state is the initial state after a Resources.evaluation plus a Resources.next
    * Note: This should never happen since the evaluation method already guarantees that the next function returns a current
    */
  final case class UnexpectedInitialState(id: Iri)
      extends ResourceRejection(s"Unexpected initial state for resource '$id'.")

  implicit val orgRejectionMapper: Mapper[OrganizationRejection, WrappedOrganizationRejection] =
    (value: OrganizationRejection) => WrappedOrganizationRejection(value)

  implicit val projectRejectionMapper: Mapper[ProjectRejection, WrappedProjectRejection] =
    (value: ProjectRejection) => WrappedProjectRejection(value)

  implicit private val resourceRejectionEncoder: Encoder.AsObject[ResourceRejection] =
    Encoder.AsObject.instance { r =>
      val tpe = ClassUtils.simpleName(r)
      val obj = JsonObject.empty.add(keywords.tpe, tpe.asJson).add("reason", r.reason.asJson)
      r match {
        case WrappedOrganizationRejection(rejection)     => rejection.asJsonObject
        case WrappedProjectRejection(rejection)          => rejection.asJsonObject
        case ResourceShaclEngineRejection(_, _, details) => obj.add("details", details.asJson)
        case InvalidJsonLdFormat(_, details)             => obj.add("details", details.reason.asJson)
        case InvalidResource(_, _, report)               => obj.add("details", report.json)
        case _                                           => obj
      }
    }

  implicit final val resourceRejectionJsonLdEncoder: JsonLdEncoder[ResourceRejection] =
    JsonLdEncoder.fromCirce(id = BNode.random, iriContext = contexts.error)
}
