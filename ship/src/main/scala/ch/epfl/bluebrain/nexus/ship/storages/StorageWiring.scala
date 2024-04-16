package ch.epfl.bluebrain.nexus.ship.storages

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.storage.StorageScopeInitialization
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.Storages
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StoragesConfig.S3StorageConfig
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageFields.S3StorageFields
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageValue
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.StorageAccess
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.JsonLdApi
import ch.epfl.bluebrain.nexus.delta.sdk.Defaults
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContext
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.ResolverContextResolution
import ch.epfl.bluebrain.nexus.delta.sourcing.Transactors
import ch.epfl.bluebrain.nexus.ship.EventClock
import ch.epfl.bluebrain.nexus.ship.config.InputConfig
import fs2.aws.s3.models.Models.BucketName

object StorageWiring {

  def storages(
      fetchContext: FetchContext,
      contextResolution: ResolverContextResolution,
      config: InputConfig,
      clock: EventClock,
      xas: Transactors
  )(implicit api: JsonLdApi): IO[Storages] = {
    val noopAccess   = new StorageAccess {
      override def apply(storage: StorageValue): IO[Unit] = IO.unit
    }
    val amazonConfig = IO.fromOption(config.storages.storageTypeConfig.amazon)(
      new IllegalArgumentException("Amazon storage type config not found")
    )
    Storages(
      fetchContext,
      contextResolution,
      amazonConfig.map(cfg => Set(cfg.defaultWritePermission, cfg.defaultReadPermission)),
      noopAccess,
      xas,
      config.storages,
      config.serviceAccount.value,
      clock
    )(api, UUIDF.random)
  }

  def s3StorageInitializer(
      storages: Storages,
      config: InputConfig
  ): IO[StorageScopeInitialization] =
    IO.fromOption(config.storages.storageTypeConfig.amazon)(
      new IllegalArgumentException("Amazon S3 configuration is missing")
    ).map { amzConfig =>
      StorageScopeInitialization.s3(
        storages,
        config.serviceAccount.value,
        defaultS3Fields(config.targetBucket, amzConfig)
      )
    }

  private def defaultS3Fields(defaultBucket: BucketName, config: S3StorageConfig) = {
    val defaults = Defaults(
      "S3 storage",
      "Default S3 storage of the Nexus service"
    )

    S3StorageFields(
      name = Some(defaults.name),
      description = Some(defaults.description),
      default = true,
      bucket = defaultBucket.value.value,
      readPermission = Some(config.defaultReadPermission),
      writePermission = Some(config.defaultWritePermission),
      maxFileSize = Some(config.defaultMaxFileSize)
    )
  }

}
