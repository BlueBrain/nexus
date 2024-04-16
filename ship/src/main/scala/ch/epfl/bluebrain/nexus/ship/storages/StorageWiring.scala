package ch.epfl.bluebrain.nexus.ship.storages

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.Storages
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageValue
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.StorageAccess
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.JsonLdApi
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContext
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.ResolverContextResolution
import ch.epfl.bluebrain.nexus.delta.sourcing.Transactors
import ch.epfl.bluebrain.nexus.ship.EventClock
import ch.epfl.bluebrain.nexus.ship.config.ShipConfig

object StorageWiring {

  def storages(
      fetchContext: FetchContext,
      contextResolution: ResolverContextResolution,
      config: ShipConfig,
      clock: EventClock,
      xas: Transactors
  )(implicit api: JsonLdApi) = {
    val noopAccess   = new StorageAccess {
      override def apply(storage: StorageValue): IO[Unit] = IO.unit
    }
    val amazonConfig = IO.fromOption(config.S3.storages.storageTypeConfig.amazon)(
      new IllegalArgumentException("Amazon storage type config not found")
    )
    Storages(
      fetchContext,
      contextResolution,
      amazonConfig.map(cfg => Set(cfg.defaultWritePermission, cfg.defaultReadPermission)),
      noopAccess,
      xas,
      config.S3.storages,
      config.serviceAccount.value,
      clock
    )(api, UUIDF.random)
  }

}
