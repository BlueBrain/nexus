package ch.epfl.bluebrain.nexus.delta.wiring

import akka.actor.typed.ActorSystem
import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.Main.pluginsMaxPriority
import ch.epfl.bluebrain.nexus.delta.config.AppConfig
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.JsonLdApi
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.routes.ResolversRoutes
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.eventlog.EventLogUtils.databaseEventLog
import ch.epfl.bluebrain.nexus.delta.sdk.fusion.FusionConfig
import ch.epfl.bluebrain.nexus.delta.sdk.identities.Identities
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.ResolverRejection.ProjectContextRejection
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.{MultiResolution, ResolverContextResolution, ResolverEvent}
import ch.epfl.bluebrain.nexus.delta.sdk.organizations.Organizations
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContext.ContextRejection
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ApiMappings
import ch.epfl.bluebrain.nexus.delta.sdk.projects.{FetchContext, ProjectReferenceFinder, Projects}
import ch.epfl.bluebrain.nexus.delta.service.resolvers.ResolversImpl.{ResolversAggregate, ResolversCache}
import ch.epfl.bluebrain.nexus.delta.service.resolvers.{ResolverEventExchange, ResolversImpl}
import ch.epfl.bluebrain.nexus.delta.service.utils.ResolverScopeInitialization
import ch.epfl.bluebrain.nexus.delta.sourcing.EventLog
import izumi.distage.model.definition.{Id, ModuleDef}
import monix.bio.UIO
import monix.execution.Scheduler

/**
  * Resolvers wiring
  */
object ResolversModule extends ModuleDef {
  implicit private val classLoader: ClassLoader = getClass.getClassLoader

  make[EventLog[Envelope[ResolverEvent]]].fromEffect { databaseEventLog[ResolverEvent](_, _) }

  make[ResolversCache].from { (config: AppConfig, as: ActorSystem[Nothing]) =>
    ResolversImpl.cache(config.resolvers)(as)
  }

  make[ResolversAggregate].fromEffect {
    (
        config: AppConfig,
        cache: ResolversCache,
        resourceIdCheck: ResourceIdCheck,
        as: ActorSystem[Nothing],
        clock: Clock[UIO]
    ) =>
      ResolversImpl.aggregate(config.resources.aggregate, cache, resourceIdCheck)(as, clock)
  }

  make[Resolvers].fromEffect {
    (
        config: AppConfig,
        eventLog: EventLog[Envelope[ResolverEvent]],
        fetchContext: FetchContext[ContextRejection],
        cache: ResolversCache,
        agg: ResolversAggregate,
        api: JsonLdApi,
        resolverContextResolution: ResolverContextResolution,
        as: ActorSystem[Nothing],
        uuidF: UUIDF,
        scheduler: Scheduler
    ) =>
      ResolversImpl(
        config.resolvers,
        eventLog,
        fetchContext.mapRejection(ProjectContextRejection),
        resolverContextResolution,
        cache,
        agg
      )(api, uuidF, scheduler, as)
  }

  many[ProjectReferenceFinder].add { (resolvers: Resolvers) =>
    Resolvers.projectReferenceFinder(resolvers)
  }

  make[MultiResolution].from {
    (
        aclCheck: AclCheck,
        fetchContext: FetchContext[ContextRejection],
        resolvers: Resolvers,
        exchanges: Set[ReferenceExchange]
    ) =>
      MultiResolution(
        fetchContext.mapRejection(ProjectContextRejection),
        ResolverResolution(aclCheck, resolvers, exchanges.toList)
      )
  }

  make[ResolversRoutes].from {
    (
        config: AppConfig,
        identities: Identities,
        aclCheck: AclCheck,
        organizations: Organizations,
        projects: Projects,
        resolvers: Resolvers,
        indexingAction: IndexingAction @Id("aggregate"),
        multiResolution: MultiResolution,
        baseUri: BaseUri,
        s: Scheduler,
        cr: RemoteContextResolution @Id("aggregate"),
        ordering: JsonKeyOrdering,
        fusionConfig: FusionConfig
    ) =>
      new ResolversRoutes(identities, aclCheck, organizations, projects, resolvers, multiResolution, indexingAction)(
        baseUri,
        config.resolvers.pagination,
        s,
        cr,
        ordering,
        fusionConfig
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
  many[PriorityRoute].add { (route: ResolversRoutes) =>
    PriorityRoute(pluginsMaxPriority + 9, route.routes, requiresStrictEntity = true)
  }

  many[ReferenceExchange].add { (resolvers: Resolvers, baseUri: BaseUri) =>
    Resolvers.referenceExchange(resolvers)(baseUri)
  }

  make[ResolverEventExchange]
  many[EventExchange].ref[ResolverEventExchange]
  many[EventExchange].named("resources").ref[ResolverEventExchange]
}
