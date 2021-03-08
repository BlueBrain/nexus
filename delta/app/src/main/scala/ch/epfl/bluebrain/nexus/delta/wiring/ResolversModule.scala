package ch.epfl.bluebrain.nexus.delta.wiring

import akka.actor.typed.ActorSystem
import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.Main.pluginsMaxPriority
import ch.epfl.bluebrain.nexus.delta.config.AppConfig
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClasspathResourceUtils.ioJsonContentOf
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.routes.ResolversRoutes
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.eventlog.EventExchange
import ch.epfl.bluebrain.nexus.delta.sdk.eventlog.EventLogUtils.databaseEventLog
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ApiMappings
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.{MultiResolution, ResolverContextResolution, ResolverEvent}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Envelope, ResourceToSchemaMappings}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.service.resolvers.ResolversImpl
import ch.epfl.bluebrain.nexus.delta.service.utils.ResolverScopeInitialization
import ch.epfl.bluebrain.nexus.delta.sourcing.EventLog
import izumi.distage.model.definition.{Id, ModuleDef}
import monix.bio.UIO
import monix.execution.Scheduler

/**
  * Resolvers wiring
  */
object ResolversModule extends ModuleDef {
  implicit private val classLoader = getClass.getClassLoader

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
        cr: RemoteContextResolution @Id("aggregate"),
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

  make[ResolverScopeInitialization].from(new ResolverScopeInitialization(_, _))

  many[ScopeInitialization].ref[ResolverScopeInitialization]

  many[ApiMappings].add(Resolvers.mappings)

  many[ResourceToSchemaMappings].add(Resolvers.resourcesToSchemas)

  many[EventExchange].add { (resolvers: Resolvers, baseUri: BaseUri, cr: RemoteContextResolution @Id("aggregate")) =>
    Resolvers.eventExchange(resolvers)(baseUri, cr)
  }

  many[RemoteContextResolution].addEffect(ioJsonContentOf("contexts/resolvers.json").map { ctx =>
    RemoteContextResolution.fixed(contexts.resolvers -> ctx.topContextValueOrEmpty)
  })
  many[PriorityRoute].add { (route: ResolversRoutes) => PriorityRoute(pluginsMaxPriority + 9, route.routes) }

}
