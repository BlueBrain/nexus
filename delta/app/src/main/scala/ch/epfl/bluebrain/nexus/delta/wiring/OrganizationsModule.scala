package ch.epfl.bluebrain.nexus.delta.wiring

import akka.actor.typed.ActorSystem
import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.config.AppConfig
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.routes.OrganizationsRoutes
import ch.epfl.bluebrain.nexus.delta.sdk.eventlog.EventLogUtils.databaseEventLog
import ch.epfl.bluebrain.nexus.delta.sdk.model.Envelope
import ch.epfl.bluebrain.nexus.delta.sdk.model.organizations.OrganizationEvent
import ch.epfl.bluebrain.nexus.delta.sdk.{Acls, Identities, Organizations, ScopeInitialization}
import ch.epfl.bluebrain.nexus.delta.service.organizations.OrganizationsImpl
import ch.epfl.bluebrain.nexus.sourcing.EventLog
import izumi.distage.model.definition.ModuleDef
import monix.bio.UIO
import monix.execution.Scheduler

/**
  * Organizations module wiring config.
  */
// $COVERAGE-OFF$
object OrganizationsModule extends ModuleDef {
  make[EventLog[Envelope[OrganizationEvent]]].fromEffect { databaseEventLog[OrganizationEvent](_, _) }

  make[Organizations].fromEffect {
    (
        config: AppConfig,
        eventLog: EventLog[Envelope[OrganizationEvent]],
        as: ActorSystem[Nothing],
        clock: Clock[UIO],
        uuidF: UUIDF,
        scheduler: Scheduler,
        scopeInitializations: Set[ScopeInitialization]
    ) =>
      OrganizationsImpl(
        config.organizations,
        eventLog,
        scopeInitializations
      )(uuidF, as, scheduler, clock)
  }

  make[OrganizationsRoutes].from {
    (
        identities: Identities,
        organizations: Organizations,
        cfg: AppConfig,
        acls: Acls,
        s: Scheduler,
        cr: RemoteContextResolution,
        ordering: JsonKeyOrdering
    ) =>
      new OrganizationsRoutes(identities, organizations, acls)(
        cfg.http.baseUri,
        cfg.organizations.pagination,
        s,
        cr,
        ordering
      )
  }

}
// $COVERAGE-ON$
