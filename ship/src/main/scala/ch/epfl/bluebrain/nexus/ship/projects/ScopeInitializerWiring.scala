package ch.epfl.bluebrain.nexus.ship.projects

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.JsonLdApi
import ch.epfl.bluebrain.nexus.delta.sdk.ScopeInitializer
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.projects.{FetchContext, ScopeInitializationErrorStore}
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.ResolverContextResolution
import ch.epfl.bluebrain.nexus.delta.sourcing.Transactors
import ch.epfl.bluebrain.nexus.ship.EventClock
import ch.epfl.bluebrain.nexus.ship.config.InputConfig
import ch.epfl.bluebrain.nexus.ship.search.SearchWiring
import ch.epfl.bluebrain.nexus.ship.storages.StorageWiring
import ch.epfl.bluebrain.nexus.ship.storages.StorageWiring.s3StorageInitializer
import ch.epfl.bluebrain.nexus.ship.views.ViewWiring.{blazegraphViews, compositeViews, elasticSearchViews, viewInitializers}

object ScopeInitializerWiring {

  def initializer(
      fetchContext: FetchContext,
      rcr: ResolverContextResolution,
      config: InputConfig,
      clock: EventClock,
      xas: Transactors
  )(implicit jsonLdApi: JsonLdApi, baseUri: BaseUri): IO[ScopeInitializer] =
    for {
      esViews        <- elasticSearchViews(fetchContext, rcr, config.eventLog, clock, UUIDF.random, xas)
      bgViews        <- blazegraphViews(fetchContext, rcr, config.eventLog, clock, UUIDF.random, xas)
      compositeViews <- compositeViews(fetchContext, rcr, config.eventLog, clock, UUIDF.random, xas)
      searchInit     <- SearchWiring.searchInitializer(
                          compositeViews,
                          config.serviceAccount.value,
                          config.search,
                          config.viewDefaults.search
                        )
      storages       <- StorageWiring.storages(fetchContext, rcr, config, clock, xas)
      storageInit    <- s3StorageInitializer(storages, config)
      allInits        = viewInitializers(esViews, bgViews, config) + searchInit + storageInit
      errorStore      = ScopeInitializationErrorStore(xas, clock)
    } yield ScopeInitializer(allInits, errorStore)

}
