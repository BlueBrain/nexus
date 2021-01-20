package ch.epfl.bluebrain.nexus.delta.wiring

import akka.actor.typed.ActorSystem
import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.config.AppConfig
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.routes.ProjectsRoutes
import ch.epfl.bluebrain.nexus.delta.sdk.eventlog.EventLogUtils.databaseEventLog
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectEvent
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Envelope}
import ch.epfl.bluebrain.nexus.delta.sdk.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.sdk.{Acls, Identities, Organizations, Projects}
import ch.epfl.bluebrain.nexus.delta.service.projects.ProjectsImpl
import ch.epfl.bluebrain.nexus.delta.service.utils.ApplyOwnerPermissions
import ch.epfl.bluebrain.nexus.sourcing.EventLog
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
        acls: Acls,
        baseUri: BaseUri,
        as: ActorSystem[Nothing],
        scheduler: Scheduler
    ) =>
      ProjectsImpl(
        config.projects,
        eventLog,
        organizations,
        ApplyOwnerPermissions(acls, config.permissions.ownerPermissions, config.serviceAccount.subject)
      )(baseUri, UUIDF.random, as, scheduler, Clock[UIO])
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

}
