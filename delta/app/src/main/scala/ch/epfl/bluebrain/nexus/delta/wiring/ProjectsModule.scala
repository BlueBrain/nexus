package ch.epfl.bluebrain.nexus.delta.wiring

import akka.actor.typed.ActorSystem
import cats.effect.Clock
import cats.effect.concurrent.Deferred
import ch.epfl.bluebrain.nexus.delta.Main.pluginsMaxPriority
import ch.epfl.bluebrain.nexus.delta.config.AppConfig
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.routes.ProjectsRoutes
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.acls.{AclCheck, Acls}
import ch.epfl.bluebrain.nexus.delta.sdk.eventlog.EventLogUtils.databaseEventLog
import ch.epfl.bluebrain.nexus.delta.sdk.fusion.FusionConfig
import ch.epfl.bluebrain.nexus.delta.sdk.identities.Identities
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.ServiceAccount
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectEvent.ProjectMarkedForDeletion
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{ApiMappings, ProjectEvent, ProjectsConfig}
import ch.epfl.bluebrain.nexus.delta.sdk.organizations.Organizations
import ch.epfl.bluebrain.nexus.delta.service.projects.ProjectsImpl.{DeletionStatusCache, ProjectsAggregate, ProjectsCache}
import ch.epfl.bluebrain.nexus.delta.service.projects._
import ch.epfl.bluebrain.nexus.delta.sourcing.config.DatabaseConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.Projection
import ch.epfl.bluebrain.nexus.delta.sourcing.{DatabaseCleanup, EventLog}
import izumi.distage.model.definition.{Id, ModuleDef}
import monix.bio.{Task, UIO}
import monix.execution.Scheduler

/**
  * Projects wiring
  */
@SuppressWarnings(Array("UnsafeTraversableMethods"))
object ProjectsModule extends ModuleDef {

  implicit private val classLoader: ClassLoader = getClass.getClassLoader

  final case class ApiMappingsCollection(value: Set[ApiMappings]) {
    def merge: ApiMappings = value.foldLeft(ApiMappings.empty)(_ + _)
  }

  make[EventLog[Envelope[ProjectEvent]]].fromEffect { databaseEventLog[ProjectEvent](_, _) }

  make[EventLog[Envelope[ProjectMarkedForDeletion]]].fromEffect { databaseEventLog[ProjectMarkedForDeletion](_, _) }

  make[ApiMappingsCollection].from { (mappings: Set[ApiMappings]) =>
    ApiMappingsCollection(mappings)
  }

  make[ProjectsCache].from { (config: AppConfig, as: ActorSystem[Nothing]) =>
    ProjectsImpl.cache(config.projects)(as)
  }

  make[DeletionStatusCache].from { (config: AppConfig, as: ActorSystem[Nothing]) =>
    ProjectsImpl.deletionCache(config.projects)(as)
  }

  make[Deferred[Task, ProjectReferenceFinder]].fromEffect(
    Deferred[Task, ProjectReferenceFinder]
  )

  make[ProjectReferenceFinder].named("aggregate").from { (referenceFinders: Set[ProjectReferenceFinder]) =>
    ProjectReferenceFinder.combine(referenceFinders.toList)
  }

  make[ProjectsAggregate].fromEffect {
    (
        config: AppConfig,
        organizations: Organizations,
        mappings: ApiMappingsCollection,
        cache: DeletionStatusCache,
        eventLog: EventLog[Envelope[ProjectEvent]],
        as: ActorSystem[Nothing],
        clock: Clock[UIO],
        uuidF: UUIDF
    ) =>
      ProjectsImpl.aggregate(
        config.projects,
        organizations,
        mappings.merge,
        CreationCooldown.validate(cache, eventLog.config)
      )(as, clock, uuidF)
  }

  make[Projects].fromEffect {
    (
        config: AppConfig,
        eventLog: EventLog[Envelope[ProjectEvent]],
        organizations: Organizations,
        quotas: Quotas,
        baseUri: BaseUri,
        as: ActorSystem[Nothing],
        uuidF: UUIDF,
        scheduler: Scheduler,
        scopeInitializations: Set[ScopeInitialization],
        mappings: ApiMappingsCollection,
        cache: ProjectsCache,
        deletionCache: DeletionStatusCache,
        agg: ProjectsAggregate
    ) =>
      ProjectsImpl(
        agg,
        config.projects,
        eventLog,
        organizations,
        quotas,
        scopeInitializations,
        mappings.merge,
        cache,
        deletionCache
      )(baseUri, uuidF, as, scheduler)
  }

  make[ResourcesDeletion].named("aggregate").from {
    (
        deletions: Set[ResourcesDeletion],
        cache: ProjectsCache,
        agg: ProjectsAggregate,
        projectsCounts: ProjectsCounts,
        dbCleanup: DatabaseCleanup
    ) =>
      ResourcesDeletion.combine(deletions, ProjectDeletion(cache, agg, projectsCounts, dbCleanup))
  }

  make[Projection[ResourcesDeletionStatusCollection]].fromEffect {
    (database: DatabaseConfig, system: ActorSystem[Nothing], clock: Clock[UIO], base: BaseUri) =>
      implicit val baseUri: BaseUri = base
      Projection(database, ResourcesDeletionStatusCollection.empty, system, clock)
  }

  make[ProjectsDeletionStream].fromEffect {
    (
        eventLog: EventLog[Envelope[ProjectMarkedForDeletion]],
        projects: Projects,
        cache: DeletionStatusCache,
        projection: Projection[ResourcesDeletionStatusCollection],
        resourcesDeletion: ResourcesDeletion @Id("aggregate"),
        clock: Clock[UIO],
        uuidF: UUIDF,
        as: ActorSystem[Nothing],
        sc: Scheduler,
        config: AppConfig
    ) =>
      Task
        .delay(
          new ProjectsDeletionStream(eventLog, projects, cache, projection, resourcesDeletion)(
            clock,
            uuidF,
            as,
            sc,
            config.projects.persistProgressConfig,
            config.projects.keyValueStore
          )
        )
        .tapEval(_.run())
  }

  make[ProjectProvisioning].from {
    (acls: Acls, projects: Projects, config: ProjectsConfig, serviceAccount: ServiceAccount) =>
      ProjectProvisioning(acls, projects, config.automaticProvisioning, serviceAccount)
  }

  make[ProjectsRoutes].from {
    (
        config: AppConfig,
        identities: Identities,
        aclCheck: AclCheck,
        projects: Projects,
        projectsCounts: ProjectsCounts,
        projectProvisioning: ProjectProvisioning,
        referenceFinder: ProjectReferenceFinder @Id("aggregate"),
        baseUri: BaseUri,
        mappings: ApiMappingsCollection,
        s: Scheduler,
        cr: RemoteContextResolution @Id("aggregate"),
        ordering: JsonKeyOrdering,
        fusionConfig: FusionConfig
    ) =>
      new ProjectsRoutes(identities, aclCheck, projects, projectsCounts, projectProvisioning)(
        baseUri,
        mappings.merge,
        config.projects,
        referenceFinder,
        s,
        cr,
        ordering,
        fusionConfig
      )
  }

  many[MetadataContextValue].addEffect(MetadataContextValue.fromFile("contexts/projects-metadata.json"))

  many[RemoteContextResolution].addEffect(
    for {
      projectsCtx       <- ContextValue.fromFile("contexts/projects.json")
      projectsMetaCtx   <- ContextValue.fromFile("contexts/projects-metadata.json")
      deletionStatusCtx <- ContextValue.fromFile("contexts/deletion-status.json")
    } yield RemoteContextResolution.fixed(
      contexts.projects         -> projectsCtx,
      contexts.projectsMetadata -> projectsMetaCtx,
      contexts.deletionStatus   -> deletionStatusCtx
    )
  )

  many[PriorityRoute].add { (route: ProjectsRoutes) =>
    PriorityRoute(pluginsMaxPriority + 7, route.routes, requiresStrictEntity = true)
  }

  make[ProjectEventExchange].from { (projects: Projects, base: BaseUri, mappings: ApiMappingsCollection) =>
    new ProjectEventExchange(projects)(base, mappings.merge)
  }
  many[EventExchange].ref[ProjectEventExchange]
  many[EventExchange].named("resources").ref[ProjectEventExchange]
}
