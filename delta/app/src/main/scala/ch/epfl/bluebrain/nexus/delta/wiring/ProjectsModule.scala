package ch.epfl.bluebrain.nexus.delta.wiring

import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.Main.pluginsMaxPriority
import ch.epfl.bluebrain.nexus.delta.config.AppConfig
import ch.epfl.bluebrain.nexus.delta.kernel.Transactors
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.routes.ProjectsRoutes
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.acls.{AclCheck, Acls}
import ch.epfl.bluebrain.nexus.delta.sdk.fusion.FusionConfig
import ch.epfl.bluebrain.nexus.delta.sdk.identities.Identities
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.ServiceAccount
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.organizations.Organizations
import ch.epfl.bluebrain.nexus.delta.sdk.projects._
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ApiMappings
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ProjectRejection.WrappedOrganizationRejection
import ch.epfl.bluebrain.nexus.delta.sdk.provisioning.ProjectProvisioning
import izumi.distage.model.definition.{Id, ModuleDef}
import monix.bio.UIO
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
      ProjectsImpl(
        organizations.fetchActiveOrganization(_).mapError(WrappedOrganizationRejection),
        scopeInitializations,
        mappings.merge,
        config.projects,
        xas
      )(baseUri, clock, uuidF)
  }

  make[ProjectsStatistics].fromEffect { (xas: Transactors, config: ProjectsConfig) =>
    ProjectsStatistics(xas, config.cache)
  }

  make[ProjectProvisioning].from {
    (acls: Acls, projects: Projects, config: AppConfig, serviceAccount: ServiceAccount) =>
      ProjectProvisioning(acls, projects, config.automaticProvisioning, serviceAccount)
  }

  make[ProjectsRoutes].from {
    (
        config: AppConfig,
        identities: Identities,
        aclCheck: AclCheck,
        projects: Projects,
        projectsStatistics: ProjectsStatistics,
        projectProvisioning: ProjectProvisioning,
        baseUri: BaseUri,
        s: Scheduler,
        cr: RemoteContextResolution @Id("aggregate"),
        ordering: JsonKeyOrdering,
        fusionConfig: FusionConfig
    ) =>
      new ProjectsRoutes(identities, aclCheck, projects, projectsStatistics, projectProvisioning)(
        baseUri,
        config.projects,
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

}
