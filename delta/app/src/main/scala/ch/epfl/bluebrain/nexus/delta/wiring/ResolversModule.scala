package ch.epfl.bluebrain.nexus.delta.wiring

import akka.actor.typed.ActorSystem
import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.Main.pluginsMaxPriority
import ch.epfl.bluebrain.nexus.delta.config.AppConfig
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.routes.ResolversRoutes
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.eventlog.EventLogUtils.databaseEventLog
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ApiMappings
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.{MultiResolution, ResolverContextResolution, ResolverEvent}
import ch.epfl.bluebrain.nexus.delta.service.resolvers.{ResolverEventExchange, ResolversImpl}
import ch.epfl.bluebrain.nexus.delta.service.utils.ResolverScopeInitialization
import ch.epfl.bluebrain.nexus.delta.sourcing.{DatabaseDefinitions, EventLog}
import ch.epfl.bluebrain.nexus.delta.sourcing.config.DatabaseFlavour
import izumi.distage.model.definition.{Id, ModuleDef}
import monix.bio.UIO
import monix.execution.Scheduler

/**
  * Resolvers wiring
  */
object ResolversModule extends ModuleDef {
  implicit private val classLoader: ClassLoader = getClass.getClassLoader

  make[EventLog[Envelope[ResolverEvent]]].fromEffect {
    (flavour: DatabaseFlavour, as: ActorSystem[Nothing], _: DatabaseDefinitions) =>
      databaseEventLog[ResolverEvent](flavour, as)
  }

  make[Resolvers].fromEffect {
    (
        config: AppConfig,
        eventLog: EventLog[Envelope[ResolverEvent]],
        orgs: Organizations,
        projects: Projects,
        resolverContextResolution: ResolverContextResolution,
        resourceIdCheck: ResourceIdCheck,
        as: ActorSystem[Nothing],
        clock: Clock[UIO],
        uuidF: UUIDF,
        scheduler: Scheduler
    ) =>
      ResolversImpl(
        config.resolvers,
        eventLog,
        orgs,
        projects,
        resolverContextResolution,
        resourceIdCheck
      )(uuidF, clock, scheduler, as)
  }

  make[MultiResolution].from {
    (acls: Acls, projects: Projects, resolvers: Resolvers, exchanges: Set[ReferenceExchange]) =>
      MultiResolution(
        projects,
        ResolverResolution(acls, resolvers, exchanges.toList)
      )
  }

  make[ResolversRoutes].from {
    (
        config: AppConfig,
        identities: Identities,
        acls: Acls,
        organizations: Organizations,
        projects: Projects,
        resolvers: Resolvers,
        indexingAction: IndexingAction @Id("aggregate"),
        multiResolution: MultiResolution,
        baseUri: BaseUri,
        s: Scheduler,
        cr: RemoteContextResolution @Id("aggregate"),
        ordering: JsonKeyOrdering
    ) =>
      new ResolversRoutes(identities, acls, organizations, projects, resolvers, multiResolution, indexingAction)(
        baseUri,
        config.resolvers.pagination,
        s,
        cr,
        ordering
      )
  }

  make[ResolverScopeInitialization]
  many[ScopeInitialization].ref[ResolverScopeInitialization]

  many[ApiMappings].add(Resolvers.mappings)

  many[ResourceToSchemaMappings].add(Resolvers.resourcesToSchemas)

  many[MetadataContextValue].addEffect(MetadataContextValue.fromFile("contexts/resolvers-metadata.json"))

  many[RemoteContextResolution].addEffect(
    for {
      resolversCtx     <- ContextValue.fromFile("contexts/resolvers.json")
      resolversMetaCtx <- ContextValue.fromFile("contexts/resolvers-metadata.json")
    } yield RemoteContextResolution.fixed(
      contexts.resolvers         -> resolversCtx,
      contexts.resolversMetadata -> resolversMetaCtx
    )
  )
  many[PriorityRoute].add { (route: ResolversRoutes) => PriorityRoute(pluginsMaxPriority + 9, route.routes) }

  many[ReferenceExchange].add { (resolvers: Resolvers, baseUri: BaseUri) =>
    Resolvers.referenceExchange(resolvers)(baseUri)
  }

  make[ResolverEventExchange]
  many[EventExchange].ref[ResolverEventExchange]
  many[EntityType].add(EntityType(Resolvers.moduleType))

}
