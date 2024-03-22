package ch.epfl.bluebrain.nexus.ship.schemas

import cats.effect.{Clock, IO}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.JsonLdApi
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
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

object SchemaWiring {

  def schemaImports(
      resourceLog: Clock[IO] => IO[ResourceLog],
      schemaLog: Clock[IO] => IO[SchemaLog],
      fetchContext: FetchContext,
      config: EventLogConfig,
      xas: Transactors
  )(implicit
      jsonLdApi: JsonLdApi
  ): Clock[IO] => IO[SchemaImports] = { clock =>
    val aclCheck  = AclCheck(AclWiring.acls(config, clock, xas))
    val resolvers = ResolverWiring.resolvers(fetchContext, config, clock, xas)
    for {
      fetchResource <- resourceLog(clock).map(FetchResource(_))
      fetchSchema   <- schemaLog(clock).map(FetchSchema(_))
    } yield SchemaImports(aclCheck, resolvers, fetchSchema, fetchResource)
  }

  private def validateSchema(implicit api: JsonLdApi): IO[ValidateSchema] = {
    val rcr = RemoteContextResolution.never
    ShaclShapesGraph.shaclShaclShapes.map(ValidateSchema(api, _, rcr))
  }

  def schemaLog(config: EventLogConfig, xas: Transactors, api: JsonLdApi): Clock[IO] => IO[SchemaLog] =
    clock =>
      for {
        validate <- validateSchema(api)
        schemaDef = Schemas.definition(validate, clock)
      } yield ScopedEventLog(schemaDef, config, xas)

}
