package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.{contexts, BlazegraphViewType}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, JsonLdContext, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.Configuration

object BlazegraphDecoderConfiguration {

  def apply(implicit rcr: RemoteContextResolution): IO[Configuration] = for {
    contextValue  <- IO.delay { ContextValue(contexts.blazegraph) }
    jsonLdContext <- JsonLdContext(contextValue)
  } yield {
    val enhancedJsonLdContext = jsonLdContext
      .addAliasIdType("IndexingBlazegraphViewValue", BlazegraphViewType.IndexingBlazegraphView.tpe)
      .addAliasIdType("AggregateBlazegraphViewValue", BlazegraphViewType.AggregateBlazegraphView.tpe)
    Configuration(enhancedJsonLdContext, "id")
  }

}
