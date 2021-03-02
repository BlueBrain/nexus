package ch.epfl.bluebrain.nexus.delta.wiring

import akka.actor.typed.ActorSystem
import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.config.AppConfig
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.routes.SchemasRoutes
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.eventlog.EventLogUtils.databaseEventLog
import ch.epfl.bluebrain.nexus.delta.sdk.model.Envelope
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ApiMappings
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.ResolverContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.model.schemas.SchemaEvent
import ch.epfl.bluebrain.nexus.delta.service.schemas.{SchemaReferenceExchange, SchemasImpl}
import ch.epfl.bluebrain.nexus.delta.sourcing.EventLog
import izumi.distage.model.definition.ModuleDef
import monix.bio.UIO

/**
  * Schemas wiring
  */
object SchemasModule extends ModuleDef {

  make[EventLog[Envelope[SchemaEvent]]].fromEffect { databaseEventLog[SchemaEvent](_, _) }

  make[Schemas].fromEffect {
    (
        config: AppConfig,
        eventLog: EventLog[Envelope[SchemaEvent]],
        organizations: Organizations,
        projects: Projects,
        schemaImports: SchemaImports,
        resolverContextResolution: ResolverContextResolution,
        clock: Clock[UIO],
        uuidF: UUIDF,
        as: ActorSystem[Nothing]
    ) =>
      SchemasImpl(
        organizations,
        projects,
        schemaImports,
        resolverContextResolution,
        config.schemas,
        eventLog
      )(uuidF, as, clock)
  }

  many[ApiMappings].add(Schemas.mappings)

  make[SchemaImports].from {
    (
        acls: Acls,
        resolvers: Resolvers,
        resources: Resources,
        schemas: Schemas
    ) =>
      SchemaImports(acls, resolvers, schemas, resources)
  }

  make[SchemasRoutes]

  make[SchemaReferenceExchange]
  many[ReferenceExchange].ref[SchemaReferenceExchange]
}
