package ch.epfl.bluebrain.nexus.delta.sdk.model.resources

import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClassUtils
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.{BNode, Iri}
import ch.epfl.bluebrain.nexus.delta.rdf.RdfError
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.shacl.ValidationReport
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
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
    * Rejection returned when attempting to create a resource with an id that already exists.
    *
    * @param id      the resource identifier
    */
  final case class ResourceAlreadyExists(id: Iri) extends ResourceRejection(s"Resource '$id' already exists.")

  /**
    * Rejection returned when attempting to update a resource with an id that doesn't exist.
    *
    * @param id      the resource identifier
    */
  final case class ResourceNotFound(id: Iri) extends ResourceRejection(s"Resource '$id' not found.")

  /**
    * Rejection returned when attempting to create a resource where the passed id does not match the id on the payload.
    *
    * @param id        the resource identifier
    * @param payloadId the resource identifier on the payload
    */
  final case class ResourceIdUnexpected(id: Iri, payloadId: Iri)
      extends ResourceRejection(s"Resource '$id' does not match resource id on payload '$payloadId'.")

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
  final case class ResourceSchemaUnexpected(id: Iri, provided: ResourceRef, expected: ResourceRef)
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
    * Signals and attempt to interact with a resource that belongs to a deprecated project.
    */
  final case class ProjectIsDeprecated(projectRef: ProjectRef)
      extends ResourceRejection(s"Project with label '$projectRef' is deprecated.")

  /**
    * Signals that an operation on a resource cannot be perform due to the fact that its project does not exist.
    */
  final case class ProjectNotFound(projectRef: ProjectRef)
      extends ResourceRejection(s"Project '$projectRef' not found.")

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

  final case class ResourceJsonLdPayloadRejection(idOpt: Option[Iri], rdfError: RdfError)
      extends ResourceRejection(s"Resource ${idOpt.fold("")(id => s"'$id'")} has invalid JSON-LD payload.")

  /**
    * Rejection returned when the returned state is the initial state after a Resources.evaluation plus a Resources.next
    * Note: This should never happen since the evaluation method already guarantees that the next function returns a current
    */
  final case class UnexpectedInitialState(id: Iri)
      extends ResourceRejection(s"Unexpected initial state for resource '$id'.")

  implicit private val resourceRejectionEncoder: Encoder.AsObject[ResourceRejection] =
    Encoder.AsObject.instance { r =>
      val tpe = ClassUtils.simpleName(r)
      val obj = JsonObject.empty.add(keywords.tpe, tpe.asJson).add("reason", r.reason.asJson)
      r match {
        case ResourceShaclEngineRejection(_, _, details) => obj.add("details", details.asJson)
        case ResourceJsonLdPayloadRejection(_, details)  => obj.add("details", details.reason.asJson)
        case InvalidResource(_, _, report)               => obj.add("details", report.json)
        case _                                           => obj
      }
    }

  implicit final val resourceRejectionJsonLdEncoder: JsonLdEncoder[ResourceRejection] =
    JsonLdEncoder.compactFromCirce(id = BNode.random, iriContext = contexts.error)

}
