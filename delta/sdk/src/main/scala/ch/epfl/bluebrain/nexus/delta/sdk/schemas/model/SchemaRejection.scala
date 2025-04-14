package ch.epfl.bluebrain.nexus.delta.sdk.schemas.model

import akka.http.scaladsl.model.StatusCodes
import ch.epfl.bluebrain.nexus.delta.kernel.error.Rejection
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClassUtils
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.rdf.shacl.ValidationReport
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.HttpResponseFields
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model.ResourceResolutionReport
import ch.epfl.bluebrain.nexus.delta.sdk.syntax.*
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{ProjectRef, ResourceRef}
import io.circe.syntax.*
import io.circe.{Encoder, Json, JsonObject}

/**
  * Enumeration of schema rejection types.
  *
  * @param reason
  *   a descriptive message as to why the rejection occurred
  */
sealed abstract class SchemaRejection(val reason: String) extends Rejection

object SchemaRejection {

  /**
    * Rejection that may occur when fetching a Schema
    */
  sealed abstract class SchemaFetchRejection(override val reason: String) extends SchemaRejection(reason)

  /**
    * Rejection returned when a subject intends to retrieve a schema at a specific revision, but the provided revision
    * does not exist.
    *
    * @param provided
    *   the provided revision
    * @param current
    *   the last known revision
    */
  final case class RevisionNotFound(provided: Int, current: Int)
      extends SchemaFetchRejection(s"Revision requested '$provided' not found, last known revision is '$current'.")

  /**
    * Rejection returned when a subject intends to retrieve a schema at a specific tag, but the provided tag does not
    * exist.
    *
    * @param tag
    *   the provided tag
    */
  final case class TagNotFound(tag: UserTag) extends SchemaFetchRejection(s"Tag requested '$tag' not found.")

  /**
    * Rejection returned when attempting to update a schema with an id that doesn't exist.
    *
    * @param id
    *   the schema identifier
    * @param project
    *   the project it belongs to
    */
  final case class SchemaNotFound(id: Iri, project: ProjectRef)
      extends SchemaFetchRejection(s"Schema '$id' not found in project '$project'.")

  /**
    * Rejection returned when attempting to interact with a schema providing an id that cannot be resolved to an Iri.
    *
    * @param id
    *   the schema identifier
    */
  final case class InvalidSchemaId(id: String)
      extends SchemaFetchRejection(s"Schema identifier '$id' cannot be expanded to an Iri.")

  /**
    * Rejection returned when attempting to create a schema but the id already exists.
    *
    * @param id
    *   the resource identifier
    * @param project
    *   the project it belongs to
    */
  final case class ResourceAlreadyExists(id: Iri, project: ProjectRef)
      extends SchemaRejection(s"Resource '$id' already exists in project '$project'.")

  /**
    * Rejection returned when attempting to create/update a schema with a reserved id.
    */
  final case class ReservedSchemaId(id: Iri)
      extends SchemaRejection(s"Schema identifier '$id' is reserved by the platform.")

  /**
    * Rejection returned when attempting to create/update a schema where the payload does not satisfy the SHACL schema
    * constrains.
    *
    * @param id
    *   the schema identifier
    * @param report
    *   the SHACL validation failure report
    */
  final case class InvalidSchema(id: Iri, report: ValidationReport)
      extends SchemaRejection(s"Schema '$id' failed to validate against the constraints defined in the SHACL schema.")

  /**
    * Rejection returned when failed to resolve some owl imports.
    *
    * @param id
    *   the schema identifier
    * @param schemaImports
    *   the schema imports that weren't successfully resolved
    * @param resourceImports
    *   the resource imports that weren't successfully resolved
    * @param nonOntologyResources
    *   resolved resources which are not ontologies
    */
  final case class InvalidSchemaResolution(
      id: Iri,
      schemaImports: Map[ResourceRef, ResourceResolutionReport],
      resourceImports: Map[ResourceRef, ResourceResolutionReport],
      nonOntologyResources: Set[ResourceRef]
  ) extends SchemaRejection(
        s"Failed to resolve imports ${(schemaImports.keySet ++ resourceImports.keySet ++ nonOntologyResources)
            .mkString("'", "', '", "'")} for schema '$id'."
      )

  /**
    * Rejection returned when attempting to create a SHACL engine.
    *
    * @param id
    *   the schema identifier
    * @param details
    *   the SHACL engine errors
    */
  final case class SchemaShaclEngineRejection(id: Iri, details: String)
      extends SchemaRejection(s"Schema '$id' failed to produce a SHACL engine for the SHACL schema.")

  /**
    * Rejection returned when attempting to update/deprecate a schema that is already deprecated.
    *
    * @param id
    *   the schema identifier
    */
  final case class SchemaIsDeprecated(id: Iri) extends SchemaFetchRejection(s"Schema '$id' is deprecated.")

  /**
    * Rejection returned when attempting to undeprecate a schema that is not deprecated.
    *
    * @param id
    *   the schema identifier
    */
  final case class SchemaIsNotDeprecated(id: Iri) extends SchemaFetchRejection(s"Schema '$id' is not deprecated.")

  /**
    * Rejection returned when a subject intends to perform an operation on the current schema, but either provided an
    * incorrect revision or a concurrent update won over this attempt.
    *
    * @param provided
    *   the provided revision
    * @param expected
    *   the expected revision
    */
  final case class IncorrectRev(provided: Int, expected: Int)
      extends SchemaRejection(
        s"Incorrect revision '$provided' provided, expected '$expected', the schema may have been updated since last seen."
      )

  implicit val schemasRejectionEncoder: Encoder.AsObject[SchemaRejection] = {
    def importsAsJson(imports: Map[ResourceRef, ResourceResolutionReport]) =
      Json.fromValues(
        imports.map { case (ref, report) =>
          Json.obj("resourceRef" -> ref.asJson, "report" -> report.asJson)
        }
      )

    Encoder.AsObject.instance { r =>
      val tpe = ClassUtils.simpleName(r)
      val obj = JsonObject.empty.add(keywords.tpe, tpe.asJson).add("reason", r.reason.asJson)
      r match {
        case SchemaShaclEngineRejection(_, details)                                           => obj.add("details", details.asJson)
        case InvalidSchema(_, report)                                                         => obj.addContext(contexts.shacl).add("details", report.json)
        case InvalidSchemaResolution(_, schemaImports, resourceImports, nonOntologyResources) =>
          obj
            .addContext(contexts.resolvers)
            .add("schemaImports", importsAsJson(schemaImports))
            .add("resourceImports", importsAsJson(resourceImports))
            .add("nonOntologyResources", nonOntologyResources.asJson)
        case _: SchemaNotFound                                                                => obj.add(keywords.tpe, "ResourceNotFound".asJson)
        case _                                                                                => obj
      }
    }
  }

  implicit final val schemasRejectionJsonLdEncoder: JsonLdEncoder[SchemaRejection] =
    JsonLdEncoder.computeFromCirce(ContextValue(contexts.error))

  implicit val responseFieldsSchemas: HttpResponseFields[SchemaRejection] =
    HttpResponseFields {
      case RevisionNotFound(_, _)      => StatusCodes.NotFound
      case TagNotFound(_)              => StatusCodes.NotFound
      case SchemaNotFound(_, _)        => StatusCodes.NotFound
      case ResourceAlreadyExists(_, _) => StatusCodes.Conflict
      case IncorrectRev(_, _)          => StatusCodes.Conflict
      case _                           => StatusCodes.BadRequest
    }
}
