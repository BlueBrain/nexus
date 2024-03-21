package ch.epfl.bluebrain.nexus.ship.schemas

import cats.effect.{Clock, IO}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.JsonLdApi
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.shacl.ShaclShapesGraph
import ch.epfl.bluebrain.nexus.delta.sdk.schemas.Schemas.SchemaLog
import ch.epfl.bluebrain.nexus.delta.sdk.schemas.{FetchSchema, Schemas, ValidateSchema}
import ch.epfl.bluebrain.nexus.delta.sourcing.{ScopedEventLog, Transactors}
import ch.epfl.bluebrain.nexus.delta.sourcing.config.EventLogConfig

object SchemaOps {

  def validateSchema(implicit api: JsonLdApi): IO[ValidateSchema] = {
    val rcr = RemoteContextResolution.never
    ShaclShapesGraph.shaclShaclShapes.map(ValidateSchema(api, _, rcr))
  }

  def schemaLog(config: EventLogConfig, clock: Clock[IO], xas: Transactors)(implicit api: JsonLdApi): IO[SchemaLog] =
    for {
      validate <- validateSchema
      schemaDef = Schemas.definition(validate, clock)
    } yield ScopedEventLog(schemaDef, config, xas)

  def fetchSchema(config: EventLogConfig, clock: Clock[IO], xas: Transactors)(implicit
      api: JsonLdApi
  ): IO[FetchSchema] =
    for {
      log <- schemaLog(config, clock, xas)
    } yield FetchSchema(log)

}
