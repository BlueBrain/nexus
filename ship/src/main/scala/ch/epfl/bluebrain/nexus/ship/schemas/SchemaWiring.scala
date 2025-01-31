package ch.epfl.bluebrain.nexus.ship.schemas

import cats.data.NonEmptyList
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.ExpandedJsonLd
import ch.epfl.bluebrain.nexus.delta.rdf.shacl.ValidationReport
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContext
import ch.epfl.bluebrain.nexus.delta.sdk.resources.FetchResource
import ch.epfl.bluebrain.nexus.delta.sdk.schemas.Schemas.SchemaLog
import ch.epfl.bluebrain.nexus.delta.sdk.schemas.{FetchSchema, SchemaImports, Schemas, ValidateSchema}
import ch.epfl.bluebrain.nexus.delta.sourcing.config.EventLogConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.{ScopedEventLog, Transactors}
import ch.epfl.bluebrain.nexus.ship.EventClock
import ch.epfl.bluebrain.nexus.ship.acls.AclWiring.alwaysAuthorize
import ch.epfl.bluebrain.nexus.ship.resolvers.ResolverWiring
import io.circe.Json

object SchemaWiring {

  def apply(config: EventLogConfig, clock: EventClock, xas: Transactors): (SchemaLog, FetchSchema) = {
    val log = schemaLog(config, clock, xas)
    (log, FetchSchema(log))
  }

  def schemaImports(
      fetchResource: FetchResource,
      fetchSchema: FetchSchema,
      fetchContext: FetchContext,
      config: EventLogConfig,
      clock: EventClock,
      xas: Transactors
  ): SchemaImports = {
    val resolvers = ResolverWiring.resolvers(fetchContext, config, clock, xas)
    SchemaImports(alwaysAuthorize, resolvers, fetchSchema, fetchResource)
  }

  private def dontValidateSchema: ValidateSchema = (_: IriOrBNode.Iri, _: NonEmptyList[ExpandedJsonLd]) =>
    IO.pure(ValidationReport(conforms = true, 1, Json.obj()))

  def schemaLog(config: EventLogConfig, clock: EventClock, xas: Transactors): SchemaLog = {
    val schemaDef = Schemas.definition(dontValidateSchema, clock)
    ScopedEventLog(schemaDef, config, xas)
  }

}
