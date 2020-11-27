package ch.epfl.bluebrain.nexus.delta.rdf.jsonld

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.{BNode, Iri}
import ch.epfl.bluebrain.nexus.delta.rdf.graph.Graph
import ch.epfl.bluebrain.nexus.delta.rdf.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.{JsonLdApi, JsonLdOptions}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context._
import ch.epfl.bluebrain.nexus.delta.rdf.{IriOrBNode, RdfError}
import io.circe.syntax._
import io.circe.{Json, JsonObject}
import monix.bio.IO

/**
  * Json-LD Compacted Document. CompactedJsonLd specific implementation is entity centric, having always only one root @id.
  */
final case class CompactedJsonLd private (rootId: IriOrBNode, ctx: ContextValue, obj: JsonObject) extends JsonLd {
  self =>

  override lazy val json: Json =
    obj.remove(keywords.context).asJson.addContext(ctx.contextObj)

  /**
    * Converts the current document to an [[ExpandedJsonLd]]
    */
  def toExpanded(implicit
      opts: JsonLdOptions,
      api: JsonLdApi,
      resolution: RemoteContextResolution
  ): IO[RdfError, ExpandedJsonLd] =
    ExpandedJsonLd(json).map(_.replaceId(rootId))

  /**
    * Converts the current document to a [[Graph]]
    */
  def toGraph(implicit
      opts: JsonLdOptions,
      api: JsonLdApi,
      resolution: RemoteContextResolution
  ): IO[RdfError, Graph] =
    toExpanded.flatMap(expanded => IO.fromEither(expanded.toGraph))

  /**
    * Merges the current document with the passed one, overriding the fields on the current with the passed.
    *
    * The rootId for the new [[CompactedJsonLd]] is provided.
    *
    * If some keys are present in both documents, the passed one will override the current ones.
    */
  def merge(rootId: IriOrBNode, other: CompactedJsonLd): CompactedJsonLd =
    CompactedJsonLd(rootId, ctx.merge(other.ctx), obj.deepMerge(other.obj))

  /**
    * Replaces the root id value and returns a new [[CompactedJsonLd]]
    *
    * @param id the new root id value
    */
  def replaceId(id: IriOrBNode): CompactedJsonLd =
    id match {
      case _ if id == rootId => self
      case iri: Iri          => copy(rootId = iri, obj = obj.replace(rootId, iri))
      case bNode: BNode      => copy(rootId = bNode, obj = obj.removeAllValues(rootId))
    }

  override def isEmpty: Boolean = obj.isEmpty
}

object CompactedJsonLd {

  /**
    * An empty [[CompactedJsonLd]] with a random blank node
    */
  val empty: CompactedJsonLd = CompactedJsonLd(BNode.random, ContextValue.empty, JsonObject.empty)

  /**
    * Creates a [[CompactedJsonLd]] document.
    *
    * @param rootId        the root id
    * @param contextValue  the context to apply in order to compact the ''input''
    * @param input         the input Json document
    * @param frameOnRootId flag to decide whether or not to frame the ''input'' context using the ''rootId''
    */
  final def apply(
      rootId: IriOrBNode,
      contextValue: ContextValue,
      input: Json,
      frameOnRootId: Boolean = false
  )(implicit
      api: JsonLdApi,
      resolution: RemoteContextResolution,
      opts: JsonLdOptions
  ): IO[RdfError, CompactedJsonLd] = {

    def computeFrame(frame: Json) = api.frame(input, frame)
    def computeCompact            = api.compact(input, contextValue)

    val result = if (frameOnRootId) frame(rootId, contextValue).fold(computeCompact)(computeFrame) else computeCompact
    result.map { compacted =>
      CompactedJsonLd(rootId, contextValue, compacted.remove(keywords.context))
    }
  }

  /**
    * Unsafely constructs a [[CompactedJsonLd]].
    *
    * @param rootId       the root id
    * @param contextValue the context used in order to build the ''compacted'' document
    * @param compacted    the already compacted document
    */
  final def unsafe(rootId: IriOrBNode, contextValue: ContextValue, compacted: JsonObject): CompactedJsonLd =
    CompactedJsonLd(rootId, contextValue, compacted)

  private def frame(id: IriOrBNode, contextValue: ContextValue) =
    id.asIri.map(iri => contextValue.contextObj deepMerge Json.obj(keywords.id -> iri.asJson))
}
