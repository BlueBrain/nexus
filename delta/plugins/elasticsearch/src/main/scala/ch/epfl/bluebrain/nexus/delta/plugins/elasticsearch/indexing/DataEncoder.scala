package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.indexing

import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.BNode
import ch.epfl.bluebrain.nexus.delta.rdf.RdfError
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.{JsonLdApi, JsonLdJavaApi}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue.ContextObject
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.sdk.views.model.IndexingData
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import io.circe.Json
import io.circe.syntax.EncoderOps
import monix.bio.IO

sealed trait DataEncoder

object DataEncoder extends DataEncoder {

  private val defaultContext =
    ContextValue(contexts.elasticsearchIndexing, contexts.indexingMetadata)

  implicit private[indexing] val api: JsonLdApi = JsonLdJavaApi.lenient

  def defaultEncoder(
      context: Option[ContextObject]
  )(implicit cr: RemoteContextResolution): IndexingData => IO[RdfError, Json] =
    (data: IndexingData) => {
      val mergedContext = context.fold(defaultContext) { defaultContext.merge(_) }
      val graph         = data.graph ++ data.metadataGraph
      if (data.source.isEmpty()) {
        graph.toCompactedJsonLd(mergedContext).map(_.obj.asJson)
      } else {
        (graph -- graph.rootTypesGraph)
          .replaceRootNode(
            BNode.random
          ) // This is done to get rid of the @id in order to avoid overriding the source @id
          .toCompactedJsonLd(mergedContext)
          .map(ld => mergeJsonLd(data.source, ld.json).removeAllKeys(keywords.context))
      }
    }

  private def mergeJsonLd(a: Json, b: Json): Json =
    if (a.isEmpty()) b
    else if (b.isEmpty()) a
    else a deepMerge b

}
