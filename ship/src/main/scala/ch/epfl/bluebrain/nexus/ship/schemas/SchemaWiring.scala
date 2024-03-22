package ch.epfl.bluebrain.nexus.ship.schemas

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.JsonLdApi
import ch.epfl.bluebrain.nexus.delta.rdf.shacl.ShaclShapesGraph
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContext
import ch.epfl.bluebrain.nexus.delta.sdk.resources.FetchResource
import ch.epfl.bluebrain.nexus.delta.sdk.resources.Resources.ResourceLog
import ch.epfl.bluebrain.nexus.delta.sdk.schemas.Schemas.SchemaLog
import ch.epfl.bluebrain.nexus.delta.sdk.schemas.{FetchSchema, SchemaImports, Schemas, ValidateSchema}
import ch.epfl.bluebrain.nexus.delta.sourcing.config.EventLogConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.{ScopedEventLog, Transactors}
import ch.epfl.bluebrain.nexus.ship.acls.AclWiring
import ch.epfl.bluebrain.nexus.ship.resolvers.ResolverWiring
import ch.epfl.bluebrain.nexus.ship.{ContextWiring, EventClock}

object SchemaWiring {

  def schemaImports(
      resourceLog: IO[ResourceLog],
      schemaLog: IO[SchemaLog],
      fetchContext: FetchContext,
      config: EventLogConfig,
      clock: EventClock,
      xas: Transactors
  )(implicit
      jsonLdApi: JsonLdApi
  ): IO[SchemaImports] = {
    val aclCheck  = AclCheck(AclWiring.acls(config, clock, xas))
    val resolvers = ResolverWiring.resolvers(fetchContext, config, clock, xas)
    for {
      fetchResource <- resourceLog.map(FetchResource(_))
      fetchSchema   <- schemaLog.map(FetchSchema(_))
    } yield SchemaImports(aclCheck, resolvers, fetchSchema, fetchResource)
  }

  private def validateSchema(implicit api: JsonLdApi): IO[ValidateSchema] =
    for {
      rcr         <- ContextWiring.remoteContextResolution
      shapesGraph <- ShaclShapesGraph.shaclShaclShapes
    } yield ValidateSchema(api, shapesGraph, rcr)

  def schemaLog(config: EventLogConfig, clock: EventClock, xas: Transactors, api: JsonLdApi): IO[SchemaLog] =
    for {
      validate <- validateSchema(api)
      schemaDef = Schemas.definition(validate, clock)
    } yield ScopedEventLog(schemaDef, config, xas)

}
