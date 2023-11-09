package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.effect.migration.toCatsIOOps
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.{contexts, BlazegraphViewType}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.JsonLdApi
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, JsonLdContext, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.Configuration

private[blazegraph] object BlazegraphDecoderConfiguration {

  def apply(implicit jsonLdApi: JsonLdApi, rcr: RemoteContextResolution): IO[Configuration] = for {
    contextValue  <- IO.delay { ContextValue(contexts.blazegraph) }
    jsonLdContext <- JsonLdContext(contextValue).toCatsIO
  } yield {
    val enhancedJsonLdContext = jsonLdContext
      .addAliasIdType("IndexingBlazegraphViewValue", BlazegraphViewType.IndexingBlazegraphView.tpe)
      .addAliasIdType("AggregateBlazegraphViewValue", BlazegraphViewType.AggregateBlazegraphView.tpe)
    Configuration(enhancedJsonLdContext, "id")
  }

}
