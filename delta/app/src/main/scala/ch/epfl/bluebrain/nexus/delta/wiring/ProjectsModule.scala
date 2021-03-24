package ch.epfl.bluebrain.nexus.delta.wiring

import akka.actor.typed.ActorSystem
import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.Main.pluginsMaxPriority
import ch.epfl.bluebrain.nexus.delta.config.AppConfig
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.routes.ProjectsRoutes
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.eventlog.EventLogUtils.databaseEventLog
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{ApiMappings, ProjectEvent}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Envelope, MetadataContextValue}
import ch.epfl.bluebrain.nexus.delta.service.projects.{ProjectEventExchange, ProjectsImpl}
import ch.epfl.bluebrain.nexus.delta.sourcing.EventLog
import izumi.distage.model.definition.{Id, ModuleDef}
import monix.bio.UIO
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

  make[Projects].fromEffect {
    (
        config: AppConfig,
        eventLog: EventLog[Envelope[ProjectEvent]],
        organizations: Organizations,
        baseUri: BaseUri,
        as: ActorSystem[Nothing],
        clock: Clock[UIO],
        uuidF: UUIDF,
        scheduler: Scheduler,
        scopeInitializations: Set[ScopeInitialization],
        mappings: ApiMappingsCollection
    ) =>
      ProjectsImpl(
        config.projects,
        eventLog,
        organizations,
        scopeInitializations,
        mappings.merge
      )(baseUri, uuidF, as, scheduler, clock)
  }

  make[ProjectsRoutes].from {
    (
        config: AppConfig,
        identities: Identities,
        acls: Acls,
        projects: Projects,
        baseUri: BaseUri,
        s: Scheduler,
        cr: RemoteContextResolution @Id("aggregate"),
        ordering: JsonKeyOrdering
    ) =>
      new ProjectsRoutes(identities, acls, projects)(baseUri, config.projects.pagination, s, cr, ordering)
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

  many[PriorityRoute].add { (route: ProjectsRoutes) => PriorityRoute(pluginsMaxPriority + 7, route.routes) }

  make[ProjectEventExchange]
  many[EventExchange].ref[ProjectEventExchange]
}
