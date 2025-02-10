package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch

import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue

package object indexing {

  val defaultIndexingContext = ContextValue(contexts.elasticsearchIndexing, contexts.indexingMetadata)

}
