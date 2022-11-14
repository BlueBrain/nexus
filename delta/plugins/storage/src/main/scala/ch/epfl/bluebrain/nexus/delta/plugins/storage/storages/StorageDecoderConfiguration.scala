package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages

import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageType
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.JsonLdApi
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, JsonLdContext, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.Configuration
import monix.bio.Task

private[storages] object StorageDecoderConfiguration {

  def apply(implicit jsonLdApi: JsonLdApi, rcr: RemoteContextResolution): Task[Configuration] =
    for {
      contextValue  <- Task.delay { ContextValue(contexts.storages) }
      jsonLdContext <- JsonLdContext(contextValue)
    } yield {
      val ctx = jsonLdContext
        .addAlias("DiskStorageFields", StorageType.DiskStorage.iri)
        .addAlias("S3StorageFields", StorageType.S3Storage.iri)
        .addAlias("RemoteDiskStorageFields", StorageType.RemoteDiskStorage.iri)
      Configuration(ctx, "id")
    }

}
