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
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{ApiMappings, ProjectEvent, ProjectsConfig}
import ch.epfl.bluebrain.nexus.delta.sdk.organizations.Organizations
import ch.epfl.bluebrain.nexus.delta.service.projects.ProjectsImpl.{ProjectsAggregate, ProjectsCache}
import ch.epfl.bluebrain.nexus.delta.service.projects._
import ch.epfl.bluebrain.nexus.delta.sourcing.EventLog
import izumi.distage.model.definition.{Id, ModuleDef}
import monix.bio.{IO, Task, UIO}
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

  make[ApiMappingsCollection].from { (mappings: Set[ApiMappings]) =>
    ApiMappingsCollection(mappings)
  }

  make[ProjectsCache].from { (config: AppConfig, as: ActorSystem[Nothing]) =>
    ProjectsImpl.cache(config.projects)(as)
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
        as: ActorSystem[Nothing],
        clock: Clock[UIO],
        uuidF: UUIDF
    ) =>
      ProjectsImpl.aggregate(
        config.projects,
        organizations,
        mappings.merge,
        _ => IO.unit
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
        cache
      )(baseUri, uuidF, as, scheduler)
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
      projectsCtx     <- ContextValue.fromFile("contexts/projects.json")
      projectsMetaCtx <- ContextValue.fromFile("contexts/projects-metadata.json")
    } yield RemoteContextResolution.fixed(
      contexts.projects         -> projectsCtx,
      contexts.projectsMetadata -> projectsMetaCtx
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
