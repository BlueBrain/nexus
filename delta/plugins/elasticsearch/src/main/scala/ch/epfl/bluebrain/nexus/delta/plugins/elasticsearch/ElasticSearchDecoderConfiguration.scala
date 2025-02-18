package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, JsonLdContext, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.Configuration

private[elasticsearch] object ElasticSearchDecoderConfiguration {

  /**
    * @return
    *   a decoder configuration that uses the elasticsearch context
    */
  def apply(implicit rcr: RemoteContextResolution): IO[Configuration] =
    for {
      contextValue  <- IO { ContextValue(contexts.elasticsearch) }
      jsonLdContext <- JsonLdContext(contextValue)
    } yield Configuration(jsonLdContext, "id")

}
