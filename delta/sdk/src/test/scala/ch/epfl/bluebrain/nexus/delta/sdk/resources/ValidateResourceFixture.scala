package ch.epfl.bluebrain.nexus.delta.sdk.resources

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.shacl.ValidationReport
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.JsonLdAssembly
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceF
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model.ResourceResolutionReport
import ch.epfl.bluebrain.nexus.delta.sdk.resources.ValidationResult._
import ch.epfl.bluebrain.nexus.delta.sdk.resources.model.ResourceRejection
import ch.epfl.bluebrain.nexus.delta.sdk.resources.SchemaClaim.DefinedSchemaClaim
import ch.epfl.bluebrain.nexus.delta.sdk.resources.model.ResourceRejection.InvalidSchemaRejection
import ch.epfl.bluebrain.nexus.delta.sdk.schemas.model.Schema
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{ProjectRef, ResourceRef}
import io.circe.Json
import io.circe.syntax.KeyOps

trait ValidateResourceFixture {

  val defaultReport: ValidationReport = ValidationReport(conforms = true, 5, Json.obj("conforms" := "true"))
  val defaultSchemaRevision           = 1

  def alwaysValidate: ValidateResource = new ValidateResource {
    override def apply(jsonld: JsonLdAssembly, schema: SchemaClaim, enforceSchema: Boolean): IO[ValidationResult] =
      IO.pure(
        schema match {
          case defined: DefinedSchemaClaim =>
            Validated(
              schema.project,
              ResourceRef.Revision(defined.schemaRef.iri, defaultSchemaRevision),
              defaultReport
            )
          case other                       => NoValidation(other.project)
        }
      )

    override def apply(
        jsonld: JsonLdAssembly,
        schema: ResourceF[Schema]
    ): IO[ValidationResult] =
      IO.pure(
        Validated(
          schema.value.project,
          ResourceRef.Revision(schema.id, defaultSchemaRevision),
          defaultReport
        )
      )
  }

  def alwaysFail(expected: ResourceRejection): ValidateResource = new ValidateResource {
    override def apply(jsonld: JsonLdAssembly, schema: SchemaClaim, enforceSchema: Boolean): IO[ValidationResult] =
      IO.raiseError(expected)

    override def apply(
        jsonld: JsonLdAssembly,
        schema: ResourceF[Schema]
    ): IO[ValidationResult] = IO.raiseError(expected)
  }

  def validateFor(validSchemas: Set[(ProjectRef, Iri)]): ValidateResource =
    new ValidateResource {
      override def apply(jsonld: JsonLdAssembly, schema: SchemaClaim, enforceSchema: Boolean): IO[ValidationResult] = {
        val project = schema.project
        schema match {
          case defined: DefinedSchemaClaim if validSchemas.contains((project, defined.schemaRef.iri)) =>
            val schemaRevision = ResourceRef.Revision(defined.schemaRef.iri, defaultSchemaRevision)
            IO.pure(Validated(project, schemaRevision, defaultReport))
          case defined: DefinedSchemaClaim                                                            =>
            IO.raiseError(InvalidSchemaRejection(defined.schemaRef, project, ResourceResolutionReport()))
          case other                                                                                  => IO.pure(NoValidation(other.project))
        }
      }

      override def apply(
          jsonld: JsonLdAssembly,
          schema: ResourceF[Schema]
      ): IO[ValidationResult] =
        IO.pure(
          Validated(
            schema.value.project,
            ResourceRef.Revision(schema.id, schema.rev),
            defaultReport
          )
        )
    }

}
