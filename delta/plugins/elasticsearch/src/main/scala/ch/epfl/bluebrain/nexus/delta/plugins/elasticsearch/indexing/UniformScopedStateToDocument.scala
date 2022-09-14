package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.indexing

import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.BNode
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.ExpandedJsonLd
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.{JsonLdApi, JsonLdJavaApi}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue.ContextObject
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoder
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.semiauto.deriveJsonLdDecoder
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import ch.epfl.bluebrain.nexus.delta.sourcing.state.UniformScopedState
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem.SuccessElem
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.{Elem, Pipe, PipeDef}
import io.circe.{Json, JsonObject}
import monix.bio.Task
import shapeless.Typeable

class UniformScopedStateToDocument(context: Option[ContextObject])(implicit cr: RemoteContextResolution) extends Pipe {
  override type In  = UniformScopedState
  override type Out = JsonObject
  override def label: Label                         = UniformScopedStateToDocument.label
  override def inType: Typeable[UniformScopedState] = Typeable[UniformScopedState]
  override def outType: Typeable[JsonObject]        = Typeable[JsonObject]

  private val defaultContext = ContextValue(contexts.elasticsearchIndexing, contexts.indexingMetadata)

  implicit private[indexing] val api: JsonLdApi = JsonLdJavaApi.lenient

  override def apply(element: SuccessElem[UniformScopedState]): Task[Elem[JsonObject]] = {
    val mergedContext = context.fold(defaultContext) { defaultContext.merge(_) }
    val graph         = element.value.graph ++ element.value.metadataGraph
    if (element.value.source.isEmpty())
      graph
        .toCompactedJsonLd(mergedContext)
        .map(ld => element.map(_ => ld.obj))
        .onErrorHandle(err => element.failed(err.reason))
    else
      (graph -- graph.rootTypesGraph)
        .replaceRootNode(BNode.random) // This is done to get rid of the @id in order to avoid overriding the source @id
        .toCompactedJsonLd(mergedContext)
        .map(ld => mergeJsonLd(element.value.source, ld.json).removeAllKeys(keywords.context))
        .map(json => json.asObject.fold(element.dropped: Elem[JsonObject])(obj => element.map(_ => obj)))
        .onErrorHandle(err => element.failed(err.reason))
  }

  private def mergeJsonLd(a: Json, b: Json): Json =
    if (a.isEmpty()) b
    else if (b.isEmpty()) a
    else a deepMerge b
}

object UniformScopedStateToDocument {

  val label: Label = Label.unsafe("uniform-scoped-state-to-document")

  def apply(implicit cr: RemoteContextResolution): UniformScopedStateToDocumentDef =
    new UniformScopedStateToDocumentDef

  class UniformScopedStateToDocumentDef(implicit cr: RemoteContextResolution) extends PipeDef {
    override type PipeType = UniformScopedStateToDocument
    override type Config   = UniformScopedStateToDocumentConfig
    override def configType: Typeable[UniformScopedStateToDocumentConfig]         = Typeable[UniformScopedStateToDocumentConfig]
    override def configDecoder: JsonLdDecoder[UniformScopedStateToDocumentConfig] =
      JsonLdDecoder[UniformScopedStateToDocumentConfig]
    override def label: Label                                                     = UniformScopedStateToDocument.label

    override def withConfig(config: UniformScopedStateToDocumentConfig): UniformScopedStateToDocument =
      new UniformScopedStateToDocument(config.context)
  }

  final case class UniformScopedStateToDocumentConfig(context: Option[ContextObject]) {
    def toJsonLd: ExpandedJsonLd = context match {
      case None      => ExpandedJsonLd.empty
      case Some(ctx) =>
        ExpandedJsonLd(
          Seq(
            ExpandedJsonLd.unsafe(
              nxv + label.value,
              JsonObject(
                (nxv + "context").toString -> Json.arr(Json.obj("@value" -> Json.fromString(ctx.value.noSpaces)))
              )
            )
          )
        )
    }

  }

  object UniformScopedStateToDocumentConfig {
    implicit val uniformScopedStateToDocumentJsonLdDecoder: JsonLdDecoder[UniformScopedStateToDocumentConfig] =
      deriveJsonLdDecoder
  }

}
