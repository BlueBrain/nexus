package ch.epfl.bluebrain.nexus.ship.projects

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.JsonLdApi
import ch.epfl.bluebrain.nexus.delta.sdk.ScopeInitializer
import ch.epfl.bluebrain.nexus.delta.sdk.projects.{FetchContext, ScopeInitializationErrorStore}
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.ResolverContextResolution
import ch.epfl.bluebrain.nexus.delta.sourcing.Transactors
import ch.epfl.bluebrain.nexus.ship.EventClock
import ch.epfl.bluebrain.nexus.ship.config.ShipConfig
import ch.epfl.bluebrain.nexus.ship.storages.StorageWiring
import ch.epfl.bluebrain.nexus.ship.views.ViewWiring.{blazegraphViews, elasticSearchViews, viewInitializers}
import ch.epfl.bluebrain.nexus.ship.storages.StorageWiring.s3StorageInitializer

object ScopeInitializerWiring {

  def initializer(
      fetchContext: FetchContext,
      rcr: ResolverContextResolution,
      config: ShipConfig,
      clock: EventClock,
      xas: Transactors
  )(implicit jsonLdApi: JsonLdApi): IO[ScopeInitializer] =
    for {
      esViews     <- elasticSearchViews(fetchContext, rcr, config.eventLog, clock, UUIDF.random, xas)
      bgViews     <- blazegraphViews(fetchContext, rcr, config.eventLog, clock, UUIDF.random, xas)
      storages    <- StorageWiring.storages(fetchContext, rcr, config, clock, xas)
      storageInit <- s3StorageInitializer(storages, config)
      allInits     = viewInitializers(esViews, bgViews, config) + storageInit
      errorStore   = ScopeInitializationErrorStore(xas, clock)
    } yield ScopeInitializer(allInits, errorStore)

}
