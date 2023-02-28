package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.indexing

import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.{JsonLdApi, JsonLdJavaApi}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sourcing.state.GraphResource
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem.SuccessElem
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Operation.Pipe
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.{Elem, PipeRef}
import io.circe.{Json, JsonObject}
import io.circe.syntax.EncoderOps
import monix.bio.Task
import shapeless.Typeable

/**
  * Pipe that transforms a [[GraphResource]] into a Json document
  * @param context
  *   a context to compute the compacted JSON-LD for of the [[GraphResource]]
  */
final class GraphResourceToDocument(context: ContextValue, includeContext: Boolean)(implicit
    cr: RemoteContextResolution
) extends Pipe {
  override type In  = GraphResource
  override type Out = Json
  override def ref: PipeRef                    = GraphResourceToDocument.ref
  override def inType: Typeable[GraphResource] = Typeable[GraphResource]
  override def outType: Typeable[Json]         = Typeable[Json]

  private val contextAsJson = context.contextObj.asJson

  implicit private val api: JsonLdApi = JsonLdJavaApi.lenient

  override def apply(element: SuccessElem[GraphResource]): Task[Elem[Json]] = {
    val graph = element.value.graph ++ element.value.metadataGraph
    if (element.value.source.isEmpty())
      graph
        .toCompactedJsonLd(context)
        .map(ld => element.map(_ => injectContext(ld.obj.asJson)))
    else {
      val id = getSourceId(element.value.source).getOrElse(element.value.id.toString)
      (graph -- graph.rootTypesGraph)
        .toCompactedJsonLd(context)
        .map(ld => injectContext(mergeJsonLd(element.value.source, ld.json)))
        .map(json => injectId(json, id))
        .map(json => if (json.isEmpty()) element.dropped else element.success(json))
    }
  }

  private def getSourceId(source: Json): Option[String] =
    source.hcursor.get[String]("@id").toOption

  private def injectId(json: Json, sourceId: String) =
    json.deepMerge(JsonObject("@id" -> Json.fromString(sourceId)).asJson)

  private def injectContext(json: Json)              =
    if (includeContext)
      json.removeAllKeys(keywords.context).deepMerge(contextAsJson)
    else
      json.removeAllKeys(keywords.context)

  private def mergeJsonLd(a: Json, b: Json): Json =
    if (a.isEmpty()) b
    else if (b.isEmpty()) a
    else a deepMerge b
}

object GraphResourceToDocument {

  val ref: PipeRef = PipeRef.unsafe("graph-resource-to-document")

}
