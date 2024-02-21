package ch.epfl.bluebrain.nexus.delta.wiring

import cats.effect.{Clock, IO}
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.Main.pluginsMaxPriority
import ch.epfl.bluebrain.nexus.delta.config.AppConfig
import ch.epfl.bluebrain.nexus.delta.kernel.utils.{ClasspathResourceLoader, UUIDF}
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.routes.ProjectsRoutes
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.acls.{AclCheck, Acls}
import ch.epfl.bluebrain.nexus.delta.sdk.deletion.{ProjectDeletionCoordinator, ProjectDeletionTask}
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaSchemeDirectives
import ch.epfl.bluebrain.nexus.delta.sdk.fusion.FusionConfig
import ch.epfl.bluebrain.nexus.delta.sdk.identities.Identities
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.ServiceAccount
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.model.metrics.ScopedEventMetricEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.organizations.Organizations
import ch.epfl.bluebrain.nexus.delta.sdk.organizations.model.OrganizationRejection
import ch.epfl.bluebrain.nexus.delta.sdk.projects._
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ProjectRejection.WrappedOrganizationRejection
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.{ApiMappings, Project, ProjectEvent, ProjectHealer, ProjectsHealth}
import ch.epfl.bluebrain.nexus.delta.sdk.provisioning.ProjectProvisioning
import ch.epfl.bluebrain.nexus.delta.sdk.quotas.Quotas
import ch.epfl.bluebrain.nexus.delta.sdk.sse.SseEncoder
import ch.epfl.bluebrain.nexus.delta.sourcing.Transactors
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Supervisor
import izumi.distage.model.definition.{Id, ModuleDef}

/**
  * Projects wiring
  */
@SuppressWarnings(Array("UnsafeTraversableMethods"))
object ProjectsModule extends ModuleDef {

  implicit private val loader: ClasspathResourceLoader = ClasspathResourceLoader.withContext(getClass)

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
        scopeInitializer: ScopeInitializer,
        mappings: ApiMappingsCollection,
        xas: Transactors,
        baseUri: BaseUri,
        clock: Clock[IO],
        uuidF: UUIDF
    ) =>
      IO.pure(
        ProjectsImpl(
          organizations
            .fetchActiveOrganization(_)
            .adaptError { case e: OrganizationRejection =>
              WrappedOrganizationRejection(e)
            },
          ValidateProjectDeletion(xas, config.projects.deletion.enabled),
          scopeInitializer,
          mappings.merge,
          config.projects.eventLog,
          xas,
          clock
        )(baseUri, uuidF)
      )
  }

  make[ProjectsHealth].from { (errorStore: ScopeInitializationErrorStore) =>
    ProjectsHealth(errorStore)
  }

  make[ProjectHealer].from(
    (errorStore: ScopeInitializationErrorStore, scopeInitializer: ScopeInitializer, serviceAccount: ServiceAccount) =>
      ProjectHealer(errorStore, scopeInitializer, serviceAccount)
  )

  make[ProjectsStatistics].fromEffect { (xas: Transactors) =>
    ProjectsStatistics(xas)
  }

  make[ProjectProvisioning].from {
    (
        acls: Acls,
        projects: Projects,
        config: AppConfig,
        serviceAccount: ServiceAccount
    ) =>
      ProjectProvisioning(acls, projects, config.automaticProvisioning, serviceAccount)
  }

  make[FetchContext].fromEffect { (organizations: Organizations, projects: Projects, quotas: Quotas) =>
    IO.pure(FetchContext(organizations, projects, quotas))
  }

  make[ProjectDeletionCoordinator].fromEffect {
    (
        projects: Projects,
        deletionTasks: Set[ProjectDeletionTask],
        config: AppConfig,
        serviceAccount: ServiceAccount,
        supervisor: Supervisor,
        xas: Transactors,
        clock: Clock[IO]
    ) =>
      ProjectDeletionCoordinator(
        projects,
        deletionTasks,
        config.projects.deletion,
        serviceAccount,
        supervisor,
        xas,
        clock
      )
  }

  make[DeltaSchemeDirectives].from { (fetchContext: FetchContext) => DeltaSchemeDirectives(fetchContext) }

  make[ProjectsRoutes].from {
    (
        config: AppConfig,
        identities: Identities,
        aclCheck: AclCheck,
        projects: Projects,
        projectsStatistics: ProjectsStatistics,
        projectProvisioning: ProjectProvisioning,
        baseUri: BaseUri,
        cr: RemoteContextResolution @Id("aggregate"),
        ordering: JsonKeyOrdering,
        fusionConfig: FusionConfig
    ) =>
      new ProjectsRoutes(identities, aclCheck, projects, projectsStatistics, projectProvisioning)(
        baseUri,
        config.projects,
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
