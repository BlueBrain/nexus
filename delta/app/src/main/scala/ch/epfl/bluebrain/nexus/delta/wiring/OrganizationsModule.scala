package ch.epfl.bluebrain.nexus.delta.wiring

import akka.actor.typed.ActorSystem
import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.config.AppConfig
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.routes.OrganizationsRoutes
import ch.epfl.bluebrain.nexus.delta.sdk.model.organizations.OrganizationEvent
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Envelope}
import ch.epfl.bluebrain.nexus.delta.sdk.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.sdk.{Acls, Identities, Organizations}
import ch.epfl.bluebrain.nexus.delta.service.organizations.{OrganizationsConfig, OrganizationsImpl}
import ch.epfl.bluebrain.nexus.sourcing.EventLog
import izumi.distage.model.definition.ModuleDef
import monix.bio.UIO
import monix.execution.Scheduler

/**
  * Organizations module wiring config.
  */
// $COVERAGE-OFF$
object OrganizationsModule extends ModuleDef {
  make[OrganizationsConfig].from((cfg: AppConfig) => cfg.organizations)

  make[EventLog[Envelope[OrganizationEvent]]].fromEffect { databaseEventLog[OrganizationEvent](_, _) }

  make[Organizations].fromEffect {
    (
        cfg: OrganizationsConfig,
        eventLog: EventLog[Envelope[OrganizationEvent]],
        as: ActorSystem[Nothing],
        scheduler: Scheduler
    ) =>
      OrganizationsImpl(cfg, eventLog)(UUIDF.random, as, scheduler, Clock[UIO])
  }

  make[OrganizationsRoutes].from {
    (
        identities: Identities,
        organizations: Organizations,
        acls:Acls,
        baseUri: BaseUri,
        cfg: OrganizationsConfig,
        s: Scheduler,
        cr: RemoteContextResolution,
        ordering: JsonKeyOrdering
    ) =>
      new OrganizationsRoutes(identities, organizations, acls)(baseUri, cfg.pagination, s, cr, ordering)
  }

}
// $COVERAGE-ON$
