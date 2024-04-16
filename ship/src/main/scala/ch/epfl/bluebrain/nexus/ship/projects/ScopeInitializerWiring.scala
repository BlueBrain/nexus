package ch.epfl.bluebrain.nexus.ship.projects

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.storage.StorageScopeInitialization
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StoragesConfig.S3StorageConfig
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageFields.S3StorageFields
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.JsonLdApi
import ch.epfl.bluebrain.nexus.delta.sdk.{Defaults, ScopeInitializer}
import ch.epfl.bluebrain.nexus.delta.sdk.projects.{FetchContext, ScopeInitializationErrorStore}
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.ResolverContextResolution
import ch.epfl.bluebrain.nexus.delta.sourcing.Transactors
import ch.epfl.bluebrain.nexus.ship.EventClock
import ch.epfl.bluebrain.nexus.ship.config.ShipConfig
import ch.epfl.bluebrain.nexus.ship.storages.StorageWiring
import ch.epfl.bluebrain.nexus.ship.views.ViewWiring.{blazegraphViews, elasticSearchViews, viewInitializers}

object ScopeInitializerWiring {

  def initializer(
      fetchContext: FetchContext,
      rcr: ResolverContextResolution,
      config: ShipConfig,
      clock: EventClock,
      xas: Transactors
  )(implicit jsonLdApi: JsonLdApi): IO[ScopeInitializer] =
    for {
      esViews    <- elasticSearchViews(fetchContext, rcr, config.eventLog, clock, UUIDF.random, xas)
      bgViews    <- blazegraphViews(fetchContext, rcr, config.eventLog, clock, UUIDF.random, xas)
      storages   <- StorageWiring.storages(fetchContext, rcr, config, clock, xas)
      amzConfig  <- IO.fromOption(config.S3.storages.storageTypeConfig.amazon)(
                      new IllegalArgumentException("Amazon S3 configuration is missing")
                    )
      storageInit = StorageScopeInitialization.s3(storages, config.serviceAccount.value, defaultS3Fields(amzConfig))
      allInits    = viewInitializers(esViews, bgViews, config) + storageInit
      errorStore  = ScopeInitializationErrorStore(xas, clock)
    } yield ScopeInitializer(allInits, errorStore)

  private def defaultS3Fields(config: S3StorageConfig) = {
    val defaults = Defaults(
      "S3 storage",
      "Default S3 storage of the Nexus service"
    )

    S3StorageFields(
      name = Some(defaults.name),
      description = Some(defaults.description),
      default = true,
      bucket = "", // TODO correct bucket
      readPermission = Some(config.defaultReadPermission),
      writePermission = Some(config.defaultWritePermission),
      maxFileSize = Some(config.defaultMaxFileSize)
    )
  }

}
