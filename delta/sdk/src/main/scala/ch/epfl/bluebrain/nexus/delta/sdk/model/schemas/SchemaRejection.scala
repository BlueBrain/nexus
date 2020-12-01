package ch.epfl.bluebrain.nexus.delta.sdk.model.schemas

import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClassUtils
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.{BNode, Iri}
import ch.epfl.bluebrain.nexus.delta.rdf.RdfError
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.rdf.shacl.ValidationReport
import ch.epfl.bluebrain.nexus.delta.sdk.Mapper
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.JsonLdRejection
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.JsonLdRejection.{InvalidId, InvalidJsonLdRejection, UnexpectedId}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Label, ResourceRef}
import ch.epfl.bluebrain.nexus.delta.sdk.model.organizations.OrganizationRejection
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{ProjectRef, ProjectRejection}
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.ResolverResolutionRejection
import io.circe.syntax._
import io.circe.{Encoder, JsonObject}

/**
  * Enumeration of schema rejection types.
  *
  * @param reason a descriptive message as to why the rejection occurred
  */
sealed abstract class SchemaRejection(val reason: String) extends Product with Serializable

object SchemaRejection {

  /**
    * Rejection returned when a subject intends to retrieve a schema at a specific revision, but the provided revision
    * does not exist.
    *
    * @param provided the provided revision
    * @param current  the last known revision
    */
  final case class RevisionNotFound(provided: Long, current: Long)
      extends SchemaRejection(s"Revision requested '$provided' not found, last known revision is '$current'.")

  /**
    * Rejection returned when a subject intends to retrieve a schema at a specific tag, but the provided tag
    * does not exist.
    *
    * @param tag the provided tag
    */
  final case class TagNotFound(tag: Label) extends SchemaRejection(s"Tag requested '$tag' not found.")

  /**
    * Rejection returned when attempting to create a schema with an id that already exists.
    *
    * @param id the schema identifier
    */
  final case class SchemaAlreadyExists(id: Iri) extends SchemaRejection(s"Schema '$id' already exists.")

  /**
    * Rejection returned when attempting to update a schema with an id that doesn't exist.
    *
    * @param id      the schema identifier
    * @param project the project it belongs to
    */
  final case class SchemaNotFound(id: Iri, project: ProjectRef)
      extends SchemaRejection(s"Schema '$id' not found in project '$project'.")

  /**
    * Rejection returned when attempting to create a schema where the passed id does not match the id on the payload.
    *
    * @param id        the schema identifier
    * @param payloadId the schema identifier on the payload
    */
  final case class UnexpectedSchemaId(id: Iri, payloadId: Iri)
      extends SchemaRejection(s"Schema '$id' does not match schema id on payload '$payloadId'.")

  /**
    * Rejection returned when attempting to interact with a schema providing an id that cannot be resolved to an Iri.
    *
    * @param id the schema identifier
    */
  final case class InvalidSchemaId(id: String)
      extends SchemaRejection(s"Schema identifier '$id' cannot be expanded to an Iri.")

  /**
    * Rejection returned when attempting to create/update a schema where the payload does not satisfy the SHACL schema constrains.
    *
    * @param id      the schema identifier
    * @param report  the SHACL validation failure report
    */
  final case class InvalidSchema(id: Iri, report: ValidationReport)
      extends SchemaRejection(s"Schema '$id' failed to validate against the constrains defined in the SHACL schema.")

  /**
    * Rejection returned when failed to resolve some owl imports.
    *
    * @param id      the schema identifier
    * @param imports the imports that weren't successfully resolved
    */
  final case class InvalidSchemaResolution(id: Iri, imports: Set[ResourceRef])
      extends SchemaRejection(s"Failed to resolve imports '${imports.mkString(", ")}' for schema '$id'.")

  /**
    * Rejection returned when attempting to create a SHACL engine.
    *
    * @param id      the schema identifier
    * @param details the SHACL engine errors
    */
  final case class SchemaShaclEngineRejection(id: Iri, details: String)
      extends SchemaRejection(s"Schema '$id' failed to produce a SHACL engine for the SHACL schema.")

  /**
    * Rejection returned when attempting to update/deprecate a schema that is already deprecated.
    *
    * @param id the schema identifier
    */
  final case class SchemaIsDeprecated(id: Iri) extends SchemaRejection(s"Schema '$id' is deprecated.")

  /**
    * Rejection returned when a subject intends to perform an operation on the current schema, but either provided an
    * incorrect revision or a concurrent update won over this attempt.
    *
    * @param provided the provided revision
    * @param expected the expected revision
    */
  final case class IncorrectRev(provided: Long, expected: Long)
      extends SchemaRejection(
        s"Incorrect revision '$provided' provided, expected '$expected', the schema may have been updated since last seen."
      )

  /**
    * Signals a rejection caused when interacting with the projects API
    */
  final case class WrappedProjectRejection(rejection: ProjectRejection) extends SchemaRejection(rejection.reason)

  /**
    * Signals a rejection caused when interacting with the organizations API
    */
  final case class WrappedOrganizationRejection(rejection: OrganizationRejection)
      extends SchemaRejection(rejection.reason)

  /**
    * Signals a rejection caused when interacting with the resolvers resolution API
    */
  final case class WrappedResolverResolutionRejection(rejection: ResolverResolutionRejection)
      extends SchemaRejection(rejection.reason)

  /**
    * Signals an error converting the source Json to JsonLD
    */
  final case class InvalidJsonLdFormat(idOpt: Option[Iri], rdfError: RdfError)
      extends SchemaRejection(s"Schema ${idOpt.fold("")(id => s"'$id'")} has invalid JSON-LD payload.")

  /**
    * Rejection returned when the returned state is the initial state after a Schemas.evaluation plus a Schemas.next
    * Note: This should never happen since the evaluation method already guarantees that the next function returns a current
    */
  final case class UnexpectedInitialState(id: Iri)
      extends SchemaRejection(s"Unexpected initial state for schema '$id'.")

  implicit private[model] val schemasRejectionEncoder: Encoder.AsObject[SchemaRejection] =
    Encoder.AsObject.instance { r =>
      val tpe = ClassUtils.simpleName(r)
      val obj = JsonObject.empty.add(keywords.tpe, tpe.asJson).add("reason", r.reason.asJson)
      r match {
        case WrappedResolverResolutionRejection(rejection) => rejection.asJsonObject
        case WrappedOrganizationRejection(rejection)       => rejection.asJsonObject
        case WrappedProjectRejection(rejection)            => rejection.asJsonObject
        case SchemaShaclEngineRejection(_, details)        => obj.add("details", details.asJson)
        case InvalidJsonLdFormat(_, details)               => obj.add("details", details.reason.asJson)
        case InvalidSchema(_, report)                      => obj.add("details", report.json)
        case _                                             => obj
      }
    }

  implicit final val schemasRejectionJsonLdEncoder: JsonLdEncoder[SchemaRejection] =
    JsonLdEncoder.fromCirce(id = BNode.random, iriContext = contexts.error)

  implicit val schemaJsonLdRejectionMapper: Mapper[InvalidJsonLdRejection, SchemaRejection] = {
    case InvalidId(id)                                     => InvalidSchemaId(id)
    case UnexpectedId(id, payloadIri)                      => UnexpectedSchemaId(id, payloadIri)
    case JsonLdRejection.InvalidJsonLdFormat(id, rdfError) => InvalidJsonLdFormat(id, rdfError)
  }

  implicit val schemaProjectRejectionMapper: Mapper[ProjectRejection, SchemaRejection] = {
    case ProjectRejection.WrappedOrganizationRejection(r) => WrappedOrganizationRejection(r)
    case value                                            => WrappedProjectRejection(value)
  }

  implicit val schemaOrgRejectionMapper: Mapper[OrganizationRejection, WrappedOrganizationRejection] =
    (value: OrganizationRejection) => WrappedOrganizationRejection(value)

  implicit val schemaResolverResolutionRejectionMapper
      : Mapper[ResolverResolutionRejection, WrappedResolverResolutionRejection] =
    (value: ResolverResolutionRejection) => WrappedResolverResolutionRejection(value)

}
