package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageType
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.JsonLdApi
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, JsonLdContext, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.Configuration

private[storages] object StorageDecoderConfiguration {

  def apply(implicit jsonLdApi: JsonLdApi, rcr: RemoteContextResolution): IO[Configuration] =
    for {
      contextValue  <- IO.delay { ContextValue(contexts.storages) }
      jsonLdContext <- JsonLdContext(contextValue)
    } yield {
      val ctx = jsonLdContext
        .addAlias("DiskStorageFields", StorageType.DiskStorage.iri)
        .addAlias("S3StorageFields", StorageType.S3Storage.iri)
        .addAlias("RemoteDiskStorageFields", StorageType.RemoteDiskStorage.iri)
      Configuration(ctx, "id")
    }
}
