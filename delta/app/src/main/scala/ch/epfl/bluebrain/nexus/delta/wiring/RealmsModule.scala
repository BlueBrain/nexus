package ch.epfl.bluebrain.nexus.delta.wiring

import akka.actor.typed.ActorSystem
import akka.http.scaladsl.model.{HttpRequest, Uri}
import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.config.{AppConfig, DatabaseFlavour}
import ch.epfl.bluebrain.nexus.delta.routes.RealmsRoutes
import ch.epfl.bluebrain.nexus.delta.routes.marshalling.CirceUnmarshalling._
import ch.epfl.bluebrain.nexus.delta.sdk.Realms
import ch.epfl.bluebrain.nexus.delta.sdk.model.realms.RealmEvent
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.PaginationConfig
import ch.epfl.bluebrain.nexus.delta.sdk.model.Envelope
import ch.epfl.bluebrain.nexus.delta.service.IndexingConfig
import ch.epfl.bluebrain.nexus.delta.service.cache.KeyValueStoreConfig
import ch.epfl.bluebrain.nexus.delta.service.http.HttpClient
import ch.epfl.bluebrain.nexus.delta.service.realms.{RealmsConfig, RealmsImpl, WellKnownResolver}
import ch.epfl.bluebrain.nexus.delta.service.utils.EventLogUtils.toEnvelope
import ch.epfl.bluebrain.nexus.sourcing.EventLog
import ch.epfl.bluebrain.nexus.sourcing.processor.AggregateConfig
import io.circe.Json
import izumi.distage.model.definition.ModuleDef
import monix.bio.UIO
import monix.execution.Scheduler

/**
  * Realms module wiring config.
  */
// $COVERAGE-OFF$
object RealmsModule extends ModuleDef {

  make[RealmsConfig].from((cfg: AppConfig) => cfg.realms)
  make[PaginationConfig].from((cfg: RealmsConfig) => cfg.pagination)
  make[KeyValueStoreConfig].from((cfg: RealmsConfig) => cfg.keyValueStore)
  make[AggregateConfig].from((cfg: RealmsConfig) => cfg.aggregate)
  make[IndexingConfig].from((cfg: RealmsConfig) => cfg.indexing)

  make[EventLog[Envelope[RealmEvent]]].fromEffect { (cfg: AppConfig, as: ActorSystem[Nothing]) =>
    cfg.database.flavour match {
      case DatabaseFlavour.Postgres  =>
        EventLog.postgresEventLog(toEnvelope[RealmEvent])(as)
      case DatabaseFlavour.Cassandra =>
        EventLog.cassandraEventLog(toEnvelope[RealmEvent])(as)
    }
  }

  make[Realms].fromEffect {
    (
        cfg: RealmsConfig,
        eventLog: EventLog[Envelope[RealmEvent]],
        as: ActorSystem[Nothing],
        scheduler: Scheduler,
        hc: HttpClient
    ) =>
      val wellKnownResolver = WellKnownResolver((uri: Uri) => hc[Json](HttpRequest(uri = uri))) _
      RealmsImpl(cfg, wellKnownResolver, eventLog)(as, scheduler, Clock[UIO])
  }

  make[RealmsRoutes]

}
// $COVERAGE-ON$
