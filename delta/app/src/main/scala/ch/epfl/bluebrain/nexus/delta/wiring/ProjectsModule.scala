package ch.epfl.bluebrain.nexus.delta.wiring

import akka.actor.typed.ActorSystem
import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.config.AppConfig
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.routes.ProjectsRoutes
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.eventlog.EventLogUtils.databaseEventLog
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectEvent
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Envelope}
import ch.epfl.bluebrain.nexus.delta.service.projects.{ProjectReferenceExchange, ProjectsImpl}
import ch.epfl.bluebrain.nexus.delta.sourcing.EventLog
import izumi.distage.model.definition.ModuleDef
import monix.bio.UIO
import monix.execution.Scheduler

/**
  * Projects wiring
  */
object ProjectsModule extends ModuleDef {

  make[EventLog[Envelope[ProjectEvent]]].fromEffect { databaseEventLog[ProjectEvent](_, _) }

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
        scopeInitializations: Set[ScopeInitialization]
    ) =>
      ProjectsImpl(
        config.projects,
        eventLog,
        organizations,
        scopeInitializations
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
        cr: RemoteContextResolution,
        ordering: JsonKeyOrdering
    ) =>
      new ProjectsRoutes(identities, acls, projects)(baseUri, config.projects.pagination, s, cr, ordering)
  }

  make[ProjectReferenceExchange]
  many[ReferenceExchange].ref[ProjectReferenceExchange]
}
