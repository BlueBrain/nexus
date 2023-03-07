package ch.epfl.bluebrain.nexus.delta.wiring

import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.Main.pluginsMaxPriority
import ch.epfl.bluebrain.nexus.delta.config.AppConfig
import ch.epfl.bluebrain.nexus.delta.kernel.database.Transactors
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.routes.ProjectsRoutes
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.acls.{AclCheck, Acls}
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaSchemeDirectives
import ch.epfl.bluebrain.nexus.delta.sdk.fusion.FusionConfig
import ch.epfl.bluebrain.nexus.delta.sdk.identities.Identities
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.ServiceAccount
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.model.metrics.ScopedEventMetricEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.organizations.Organizations
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContext.ContextRejection
import ch.epfl.bluebrain.nexus.delta.sdk.projects._
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ProjectRejection.WrappedOrganizationRejection
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.{ApiMappings, Project, ProjectEvent}
import ch.epfl.bluebrain.nexus.delta.sdk.provisioning.ProjectProvisioning
import ch.epfl.bluebrain.nexus.delta.sdk.quotas.Quotas
import ch.epfl.bluebrain.nexus.delta.sdk.sse.SseEncoder
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

  make[ApiMappingsCollection].from { (mappings: Set[ApiMappings]) =>
    ApiMappingsCollection(mappings)
  }

  make[Projects].fromEffect {
    (
        config: AppConfig,
        organizations: Organizations,
        scopeInitializations: Set[ScopeInitialization],
        mappings: ApiMappingsCollection,
        xas: Transactors,
        baseUri: BaseUri,
        clock: Clock[UIO],
        uuidF: UUIDF
    ) =>
      Task.pure(
        ProjectsImpl(
          organizations.fetchActiveOrganization(_).mapError(WrappedOrganizationRejection),
          scopeInitializations,
          mappings.merge,
          config.projects,
          xas
        )(baseUri, clock, uuidF)
      )
  }

  make[ProjectsStatistics].fromEffect { (xas: Transactors) =>
    ProjectsStatistics(xas)
  }

  make[ProjectProvisioning].from {
    (acls: Acls, projects: Projects, config: AppConfig, serviceAccount: ServiceAccount) =>
      ProjectProvisioning(acls, projects, config.automaticProvisioning, serviceAccount)
  }

  make[FetchContext[ContextRejection]].fromEffect {
    (organizations: Organizations, projects: Projects, quotas: Quotas) =>
      Task.pure(FetchContext(organizations, projects, quotas))
  }

  make[ProjectContextCache].fromEffect { (fetchContext: FetchContext[ContextRejection]) =>
    ProjectContextCache(fetchContext)
  }

  make[UUIDCache].fromEffect { (config: AppConfig, xas: Transactors) =>
    UUIDCache(config.projects.cache, config.organizations.cache, xas)
  }

  make[DeltaSchemeDirectives].from {
    (fetchContext: FetchContext[ContextRejection], uuidCache: UUIDCache, s: Scheduler) =>
      DeltaSchemeDirectives(fetchContext, uuidCache)(s)
  }

  make[ProjectsRoutes].from {
    (
        config: AppConfig,
        identities: Identities,
        aclCheck: AclCheck,
        projects: Projects,
        projectsStatistics: ProjectsStatistics,
        projectProvisioning: ProjectProvisioning,
        schemeDirectives: DeltaSchemeDirectives,
        baseUri: BaseUri,
        s: Scheduler,
        cr: RemoteContextResolution @Id("aggregate"),
        ordering: JsonKeyOrdering,
        fusionConfig: FusionConfig
    ) =>
      new ProjectsRoutes(identities, aclCheck, projects, projectsStatistics, projectProvisioning, schemeDirectives)(
        baseUri,
        config.projects,
        s,
        cr,
        ordering,
        fusionConfig
      )
  }

  many[SseEncoder[_]].add { base: BaseUri => ProjectEvent.sseEncoder(base) }

  many[ScopedEventMetricEncoder[_]].add { base: BaseUri => ProjectEvent.projectEventMetricEncoder(base) }

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

  make[Project.Shift].from { (projects: Projects, mappings: ApiMappingsCollection, base: BaseUri) =>
    Project.shift(projects, mappings.merge)(base)
  }

  many[ResourceShift[_, _, _]].ref[Project.Shift]

}
