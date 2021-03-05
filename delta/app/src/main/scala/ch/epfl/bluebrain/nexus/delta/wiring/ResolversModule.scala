package ch.epfl.bluebrain.nexus.delta.wiring

import akka.actor.typed.ActorSystem
import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.config.AppConfig
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.routes.ResolversRoutes
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.eventlog.EventLogUtils.databaseEventLog
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ApiMappings
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.{MultiResolution, ResolverContextResolution, ResolverEvent}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Envelope, ResourceToSchemaMappings}
import ch.epfl.bluebrain.nexus.delta.service.resolvers.{ResolverReferenceExchange, ResolversImpl}
import ch.epfl.bluebrain.nexus.delta.service.utils.ResolverScopeInitialization
import ch.epfl.bluebrain.nexus.delta.sourcing.EventLog
import izumi.distage.model.definition.ModuleDef
import monix.bio.UIO
import monix.execution.Scheduler

/**
  * Resolvers wiring
  */
object ResolversModule extends ModuleDef {

  make[EventLog[Envelope[ResolverEvent]]].fromEffect { databaseEventLog[ResolverEvent](_, _) }

  make[Resolvers].fromEffect {
    (
        config: AppConfig,
        eventLog: EventLog[Envelope[ResolverEvent]],
        projects: Projects,
        resolverContextResolution: ResolverContextResolution,
        as: ActorSystem[Nothing],
        clock: Clock[UIO],
        uuidF: UUIDF,
        scheduler: Scheduler
    ) =>
      ResolversImpl(
        config.resolvers,
        eventLog,
        projects,
        resolverContextResolution
      )(uuidF, clock, scheduler, as)
  }

  many[ApiMappings].add(Resolvers.mappings)

  many[ResourceToSchemaMappings].add(Resolvers.resourcesToSchemas)

  make[MultiResolution].from {
    (acls: Acls, projects: Projects, resolvers: Resolvers, resources: Resources, schemas: Schemas) =>
      MultiResolution(
        projects,
        ResourceResolution.dataResource(acls, resolvers, resources),
        ResourceResolution.schemaResource(acls, resolvers, schemas)
      )
  }

  make[ResolversRoutes].from {
    (
        config: AppConfig,
        identities: Identities,
        acls: Acls,
        projects: Projects,
        resolvers: Resolvers,
        multiResolution: MultiResolution,
        baseUri: BaseUri,
        s: Scheduler,
        cr: RemoteContextResolution,
        ordering: JsonKeyOrdering
    ) =>
      new ResolversRoutes(identities, acls, projects, resolvers, multiResolution)(
        baseUri,
        config.resolvers.pagination,
        s,
        cr,
        ordering
      )
  }

  make[ResolverScopeInitialization]
  many[ScopeInitialization].ref[ResolverScopeInitialization]

  make[ResolverReferenceExchange]
  many[ReferenceExchange].ref[ResolverReferenceExchange]
}
