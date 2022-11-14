package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph

import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.{contexts, BlazegraphViewType}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.JsonLdApi
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, JsonLdContext, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.Configuration
import monix.bio.Task

object BlazegraphDecoderConfiguration {

  def apply(implicit jsonLdApi: JsonLdApi, rcr: RemoteContextResolution): Task[Configuration] = for {
    contextValue  <- Task.delay { ContextValue(contexts.blazegraph) }
    jsonLdContext <- JsonLdContext(contextValue)
  } yield {
    val enhancedJsonLdContext = jsonLdContext
      .addAliasIdType("IndexingBlazegraphViewValue", BlazegraphViewType.IndexingBlazegraphView.tpe)
      .addAliasIdType("AggregateBlazegraphViewValue", BlazegraphViewType.AggregateBlazegraphView.tpe)
    Configuration(enhancedJsonLdContext, "id")
  }

}
