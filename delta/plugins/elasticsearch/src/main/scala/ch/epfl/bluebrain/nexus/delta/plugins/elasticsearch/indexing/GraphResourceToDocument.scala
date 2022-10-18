package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.indexing

import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.BNode
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.{JsonLdApi, JsonLdJavaApi}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue.ContextObject
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import ch.epfl.bluebrain.nexus.delta.sourcing.state.GraphResource
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem.SuccessElem
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Operation.Pipe
import io.circe.Json
import io.circe.syntax.EncoderOps
import monix.bio.Task
import shapeless.Typeable

class GraphResourceToDocument(context: Option[ContextObject])(implicit cr: RemoteContextResolution) extends Pipe {
  override type In  = GraphResource
  override type Out = Json
  override def label: Label                    = GraphResourceToDocument.label
  override def inType: Typeable[GraphResource] = Typeable[GraphResource]
  override def outType: Typeable[Json]         = Typeable[Json]

  private val defaultContext = ContextValue(contexts.elasticsearchIndexing, contexts.indexingMetadata)

  implicit private val api: JsonLdApi = JsonLdJavaApi.lenient

  override def apply(element: SuccessElem[GraphResource]): Task[Elem[Json]] = {
    val mergedContext = context.fold(defaultContext) { defaultContext.merge(_) }
    val graph         = element.value.graph ++ element.value.metadataGraph
    if (element.value.source.isEmpty())
      graph
        .toCompactedJsonLd(mergedContext)
        .map(ld => element.map(_ => ld.obj.asJson))
    else
      (graph -- graph.rootTypesGraph)
        .replaceRootNode(BNode.random) // This is done to get rid of the @id in order to avoid overriding the source @id
        .toCompactedJsonLd(mergedContext)
        .map(ld => mergeJsonLd(element.value.source, ld.json).removeAllKeys(keywords.context))
        .map(json => if (json.isEmpty()) element.dropped else element.success(json))
  }

  private def mergeJsonLd(a: Json, b: Json): Json =
    if (a.isEmpty()) b
    else if (b.isEmpty()) a
    else a deepMerge b
}

object GraphResourceToDocument {

  val label: Label = Label.unsafe("graph-resource-to-document")

}
