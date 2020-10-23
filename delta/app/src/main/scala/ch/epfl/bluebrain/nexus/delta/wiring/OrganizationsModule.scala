package ch.epfl.bluebrain.nexus.delta.wiring

import akka.actor.typed.ActorSystem
import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.config.{AppConfig, DatabaseFlavour}
import ch.epfl.bluebrain.nexus.delta.routes.OrganizationsRoutes
import ch.epfl.bluebrain.nexus.delta.sdk.Organizations
import ch.epfl.bluebrain.nexus.delta.sdk.model.Envelope
import ch.epfl.bluebrain.nexus.delta.sdk.model.organizations.OrganizationEvent
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.PaginationConfig
import ch.epfl.bluebrain.nexus.delta.sdk.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.service.IndexingConfig
import ch.epfl.bluebrain.nexus.delta.service.cache.KeyValueStoreConfig
import ch.epfl.bluebrain.nexus.delta.service.organizations.{OrganizationsConfig, OrganizationsImpl}
import ch.epfl.bluebrain.nexus.delta.service.utils.EventLogUtils.toEnvelope
import ch.epfl.bluebrain.nexus.sourcing.EventLog
import ch.epfl.bluebrain.nexus.sourcing.processor.AggregateConfig
import izumi.distage.model.definition.ModuleDef
import monix.bio.UIO
import monix.execution.Scheduler

/**
  * Organizations module wiring config.
  */
// $COVERAGE-OFF$
object OrganizationsModule extends ModuleDef {

  make[OrganizationsConfig].from((cfg: AppConfig) => cfg.organizations)
  make[PaginationConfig].from((cfg: OrganizationsConfig) => cfg.pagination)
  make[PaginationConfig].from((cfg: OrganizationsConfig) => cfg.pagination)
  make[KeyValueStoreConfig].from((cfg: OrganizationsConfig) => cfg.keyValueStore)
  make[AggregateConfig].from((cfg: OrganizationsConfig) => cfg.aggregate)
  make[IndexingConfig].from((cfg: OrganizationsConfig) => cfg.indexing)

  make[EventLog[Envelope[OrganizationEvent]]].fromEffect { (cfg: AppConfig, as: ActorSystem[Nothing]) =>
    cfg.database.flavour match {
      case DatabaseFlavour.Postgres  =>
        EventLog.postgresEventLog(toEnvelope[OrganizationEvent])(as)
      case DatabaseFlavour.Cassandra =>
        EventLog.cassandraEventLog(toEnvelope[OrganizationEvent])(as)
    }
  }

  make[Organizations].fromEffect {
    (
        cfg: OrganizationsConfig,
        eventLog: EventLog[Envelope[OrganizationEvent]],
        as: ActorSystem[Nothing],
        scheduler: Scheduler
    ) =>
      OrganizationsImpl(cfg, eventLog)(UUIDF.random, as, scheduler, Clock[UIO])
  }

  make[OrganizationsRoutes]

}
// $COVERAGE-ON$
