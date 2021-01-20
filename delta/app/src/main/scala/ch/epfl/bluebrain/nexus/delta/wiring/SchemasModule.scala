package ch.epfl.bluebrain.nexus.delta.wiring

import akka.actor.typed.ActorSystem
import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.config.AppConfig
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.routes.SchemasRoutes
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.eventlog.EventLogUtils.databaseEventLog
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.ResolverContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.model.schemas.SchemaEvent
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Envelope}
import ch.epfl.bluebrain.nexus.delta.sdk.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.service.schemas.SchemasImpl
import ch.epfl.bluebrain.nexus.sourcing.EventLog
import izumi.distage.model.definition.ModuleDef
import monix.bio.UIO
import monix.execution.Scheduler

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
        as: ActorSystem[Nothing]
    ) =>
      SchemasImpl(
        organizations,
        projects,
        schemaImports,
        resolverContextResolution,
        config.schemas.aggregate,
        eventLog
      )(UUIDF.random, as, Clock[UIO])
  }

  make[SchemaImports].from {
    (
        acls: Acls,
        resolvers: Resolvers,
        resources: Resources,
        schemas: Schemas
    ) =>
      SchemaImports(acls, resolvers, schemas, resources)
  }

  make[SchemasRoutes].from {
    (
        identities: Identities,
        acls: Acls,
        organizations: Organizations,
        projects: Projects,
        schemas: Schemas,
        baseUri: BaseUri,
        s: Scheduler,
        cr: RemoteContextResolution,
        ordering: JsonKeyOrdering
    ) =>
      new SchemasRoutes(identities, acls, organizations, projects, schemas)(baseUri, s, cr, ordering)
  }

}
