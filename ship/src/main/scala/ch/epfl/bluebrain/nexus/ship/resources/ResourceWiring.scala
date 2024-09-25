package ch.epfl.bluebrain.nexus.ship.resources

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.rdf.shacl.ValidationReport
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.JsonLdAssembly
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceF
import ch.epfl.bluebrain.nexus.delta.sdk.resources.Resources.ResourceLog
import ch.epfl.bluebrain.nexus.delta.sdk.resources.SchemaClaim.DefinedSchemaClaim
import ch.epfl.bluebrain.nexus.delta.sdk.resources.ValidationResult.{NoValidation, Validated}
import ch.epfl.bluebrain.nexus.delta.sdk.resources._
import ch.epfl.bluebrain.nexus.delta.sdk.schemas.FetchSchema
import ch.epfl.bluebrain.nexus.delta.sdk.schemas.model.Schema
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ResourceRef
import ch.epfl.bluebrain.nexus.delta.sourcing.{ScopedEventLog, Transactors}
import ch.epfl.bluebrain.nexus.ship.EventClock
import ch.epfl.bluebrain.nexus.ship.config.InputConfig
import io.circe.Json
import io.circe.syntax.KeyOps

object ResourceWiring {

  def apply(
      fetchSchema: FetchSchema,
      config: InputConfig,
      clock: EventClock,
      xas: Transactors
  ): (ResourceLog, FetchResource) = {
    val detectChange = DetectChange(false)

    val validation  = alwaysValidateResource(fetchSchema)
    val resourceDef = Resources.definition(validation, detectChange, clock)

    val log = ScopedEventLog(resourceDef, config.eventLog, xas)
    (log, FetchResource(log))
  }

  private def alwaysValidateResource(fetchSchema: FetchSchema): ValidateResource = new ValidateResource {
    val defaultReport: ValidationReport = ValidationReport(conforms = true, 5, Json.obj("conforms" := "true"))

    override def apply(jsonld: JsonLdAssembly, schema: SchemaClaim, enforceSchema: Boolean): IO[ValidationResult] =
      schema match {
        case defined: DefinedSchemaClaim =>
          fetchSchema.option(defined.schemaRef, schema.project).flatMap {
            case Some(value) =>
              IO.pure {
                Validated(
                  schema.project,
                  ResourceRef.Revision(value.id, value.rev),
                  defaultReport
                )
              }
            case None        =>
              IO.pure(
                Validated(
                  schema.project,
                  ResourceRef.Revision(defined.schemaRef.iri, 1),
                  defaultReport
                )
              )
          }
        case other                       => IO.pure(NoValidation(other.project))
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
