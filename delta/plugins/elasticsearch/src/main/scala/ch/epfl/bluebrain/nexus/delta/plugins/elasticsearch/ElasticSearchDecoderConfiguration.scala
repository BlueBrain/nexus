package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch

import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.JsonLdApi
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, JsonLdContext, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.Configuration
import monix.bio.Task

private[elasticsearch] object ElasticSearchDecoderConfiguration {

  /**
    * @return
    *   a decoder configuration that uses the elasticsearch context
    */
  def apply(implicit jsonLdApi: JsonLdApi, rcr: RemoteContextResolution): Task[Configuration] =
    for {
      contextValue  <- Task.delay { ContextValue(contexts.elasticsearch) }
      jsonLdContext <- JsonLdContext(contextValue)
    } yield Configuration(jsonLdContext, "id")

}
