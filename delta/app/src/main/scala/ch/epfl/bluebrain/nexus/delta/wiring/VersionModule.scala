package ch.epfl.bluebrain.nexus.delta.wiring

import akka.actor.typed.ActorSystem
import ch.epfl.bluebrain.nexus.delta.config.AppConfig
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.routes.VersionRoutes
import ch.epfl.bluebrain.nexus.delta.sdk.model.ComponentDescription.PluginDescription
import ch.epfl.bluebrain.nexus.delta.sdk.{Acls, Identities, ServiceDependency}
import ch.epfl.bluebrain.nexus.delta.service.database.{CassandraServiceDependency, PostgresServiceDependency}
import ch.epfl.bluebrain.nexus.delta.sourcing.config.DatabaseFlavour.{Cassandra, Postgres}
import izumi.distage.model.definition.{Id, ModuleDef}
import monix.execution.Scheduler

/**
  * Version module wiring config.
  */
// $COVERAGE-OFF$
object VersionModule extends ModuleDef {

  many[ServiceDependency].add { (cfg: AppConfig, system: ActorSystem[Nothing]) =>
    cfg.database.flavour match {
      case Postgres  => new PostgresServiceDependency(cfg.database.postgres)
      case Cassandra => new CassandraServiceDependency()(system)
    }
  }

  make[VersionRoutes].from {
    (
        cfg: AppConfig,
        identities: Identities,
        acls: Acls,
        plugins: List[PluginDescription],
        dependencies: Set[ServiceDependency],
        s: Scheduler,
        cr: RemoteContextResolution @Id("aggregate"),
        ordering: JsonKeyOrdering
    ) =>
      VersionRoutes(identities, acls, plugins, dependencies, cfg.description)(cfg.http.baseUri, s, cr, ordering)
  }

}
// $COVERAGE-ON$
