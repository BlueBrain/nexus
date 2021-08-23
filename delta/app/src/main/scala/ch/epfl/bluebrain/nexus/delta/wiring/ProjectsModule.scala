package ch.epfl.bluebrain.nexus.delta.wiring

import akka.actor.typed.ActorSystem
import cats.effect.Clock
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.Main.pluginsMaxPriority
import ch.epfl.bluebrain.nexus.delta.config.AppConfig
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.routes.ProjectsRoutes
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.eventlog.EventLogUtils.databaseEventLog
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{ApiMappings, ProjectEvent, ProjectsConfig}
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.service.projects.ProjectsImpl.{DeletionStatusCache, ProjectsAggregate, ProjectsCache}
import ch.epfl.bluebrain.nexus.delta.service.projects._
import ch.epfl.bluebrain.nexus.delta.sourcing.config.DatabaseConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.Projection
import ch.epfl.bluebrain.nexus.delta.sourcing.{DatabaseCleanup, EventLog}
import ch.epfl.bluebrain.nexus.delta.wiring.DeltaModule.ResourcesDeletionWithPriority
import izumi.distage.model.definition.{Id, ModuleDef}
import monix.bio.{Task, UIO}
import monix.execution.Scheduler

/**
  * Projects wiring
  */
object ProjectsModule extends ModuleDef {

  implicit private val classLoader: ClassLoader = getClass.getClassLoader

  final case class ApiMappingsCollection(value: Set[ApiMappings]) {
    def merge: ApiMappings = value.foldLeft(ApiMappings.empty)(_ + _)
  }

  make[EventLog[Envelope[ProjectEvent]]].fromEffect { databaseEventLog[ProjectEvent](_, _) }

  make[ApiMappingsCollection].from { (mappings: Set[ApiMappings]) =>
    ApiMappingsCollection(mappings)
  }

  make[ProjectsCache].from { (config: AppConfig, as: ActorSystem[Nothing]) =>
    ProjectsImpl.cache(config.projects)(as)
  }

  make[DeletionStatusCache].from { (config: AppConfig, as: ActorSystem[Nothing]) =>
    ProjectsImpl.deletionCache(config.projects)(as)
  }

  make[ProjectsAggregate].fromEffect {
    (
        config: AppConfig,
        organizations: Organizations,
        mappings: ApiMappingsCollection,
        as: ActorSystem[Nothing],
        clock: Clock[UIO],
        uuidF: UUIDF
    ) => ProjectsImpl.aggregate(config.projects, organizations, mappings.merge)(as, clock, uuidF)
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

  many[ResourcesDeletionWithPriority].add {
    (cache: ProjectsCache, agg: ProjectsAggregate, dbCleanup: DatabaseCleanup) =>
      // This priority has to be bigger than any other module, since we want projects to be handled after other resources
      100 -> ProjectDeletion(cache, agg, dbCleanup)
  }

  make[ResourcesDeletion].fromEffect { (deletions: Set[ResourcesDeletionWithPriority]) =>
    deletions.toList.sortBy { case (priority, _) => priority } match {
      case (_, head) :: tail =>
        val priorities = deletions.map { case (priority, _) => priority }.toList.distinct
        Task
          .raiseWhen(priorities.size < deletions.size)(
            new IllegalArgumentException(
              s"some of the ResourcesDeletion priorities are duplicated '${priorities.mkString(",")}'"
            )
          )
          .as(ResourcesDeletion.combine(NonEmptyList(head, tail.map(_._2))))
      case Nil               => Task.raiseError(new IllegalArgumentException("No ResourcesDeletion found"))
    }
  }

  make[Projection[ResourcesDeletionStatusCollection]].fromEffect {
    (database: DatabaseConfig, system: ActorSystem[Nothing], clock: Clock[UIO], base: BaseUri) =>
      implicit val baseUri: BaseUri = base
      Projection(database, ResourcesDeletionStatusCollection.empty, system, clock)
  }

  make[ProjectsDeletionStream].fromEffect {
    (
        projects: Projects,
        cache: DeletionStatusCache,
        projection: Projection[ResourcesDeletionStatusCollection],
        resourcesDeletion: ResourcesDeletion,
        clock: Clock[UIO],
        uuidF: UUIDF,
        as: ActorSystem[Nothing],
        sc: Scheduler,
        config: AppConfig
    ) =>
      Task
        .delay(
          new ProjectsDeletionStream(projects, cache, projection, resourcesDeletion)(
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

  make[ProjectProvisioning].from { (acls: Acls, projects: Projects, config: ProjectsConfig) =>
    ProjectProvisioning(acls, projects, config.automaticProvisioning)
  }

  make[ProjectsRoutes].from {
    (
        config: AppConfig,
        identities: Identities,
        acls: Acls,
        projects: Projects,
        projectsCounts: ProjectsCounts,
        projectProvisioning: ProjectProvisioning,
        baseUri: BaseUri,
        mappings: ApiMappingsCollection,
        s: Scheduler,
        cr: RemoteContextResolution @Id("aggregate"),
        ordering: JsonKeyOrdering
    ) =>
      new ProjectsRoutes(identities, acls, projects, projectsCounts, projectProvisioning)(
        baseUri,
        mappings.merge,
        config.projects,
        s,
        cr,
        ordering
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

  many[PriorityRoute].add { (route: ProjectsRoutes) => PriorityRoute(pluginsMaxPriority + 7, route.routes) }

  make[ProjectEventExchange].from { (projects: Projects, base: BaseUri, mappings: ApiMappingsCollection) =>
    new ProjectEventExchange(projects)(base, mappings.merge)
  }
  many[EventExchange].ref[ProjectEventExchange]
}
