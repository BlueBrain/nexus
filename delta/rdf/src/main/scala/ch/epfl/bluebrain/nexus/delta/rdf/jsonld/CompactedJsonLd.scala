package ch.epfl.bluebrain.nexus.delta.rdf.jsonld

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.{BNode, Iri}
import ch.epfl.bluebrain.nexus.delta.rdf.graph.Graph
import ch.epfl.bluebrain.nexus.delta.rdf.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.{JsonLdApi, JsonLdOptions}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context._
import io.circe.syntax._
import io.circe._

/**
  * Json-LD Compacted Document. CompactedJsonLd specific implementation is entity centric, having always only one root
  * \@id.
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
  ): IO[ExpandedJsonLd] =
    ExpandedJsonLd(json).map(_.replaceId(rootId))

  /**
    * Converts the current document to a [[Graph]]
    */
  def toGraph(implicit
      opts: JsonLdOptions,
      api: JsonLdApi,
      resolution: RemoteContextResolution
  ): IO[Graph] = toExpanded.flatMap(_.toGraph)

  /**
    * Merges the current document with the passed one, overriding the fields on the current with the passed.
    *
    * The rootId for the new [[CompactedJsonLd]] is provided.
    *
    * If some keys are present in both documents, the passed one will override the current ones.
    */
  def merge(rootId: IriOrBNode, other: CompactedJsonLd): CompactedJsonLd =
    rootId match {
      case iri: Iri if self.rootId.isBNode || self.isEmpty =>
        val mergedObj = obj.deepMerge(other.obj).deepMerge(JsonObject(keywords.id -> iri.asJson))
        CompactedJsonLd(rootId, ctx.merge(other.ctx), mergedObj)
      case _                                               =>
        CompactedJsonLd(rootId, ctx.merge(other.ctx), obj.deepMerge(other.obj))
    }

  /**
    * Replaces the root id value and returns a new [[CompactedJsonLd]]
    *
    * @param id
    *   the new root id value
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
    * @param rootId
    *   the root id
    * @param contextValue
    *   the context to apply in order to compact the ''input''
    * @param input
    *   the input Json document
    */
  final def apply(
      rootId: IriOrBNode,
      contextValue: ContextValue,
      input: Json
  )(implicit api: JsonLdApi, rcr: RemoteContextResolution, opts: JsonLdOptions): IO[CompactedJsonLd] =
    api
      .compact(input, contextValue)
      .map { compacted =>
        CompactedJsonLd(rootId, contextValue, compacted.remove(keywords.context))
      }

  /**
    * Creates a [[CompactedJsonLd]] document framed on the passed ''rootId''.
    *
    * @param rootId
    *   the root id
    * @param contextValue
    *   the context to apply in order to compact the ''input''
    * @param input
    *   the input Json document
    */
  final def frame(
      rootId: IriOrBNode,
      contextValue: ContextValue,
      input: Json
  )(implicit api: JsonLdApi, rcr: RemoteContextResolution, opts: JsonLdOptions): IO[CompactedJsonLd] =
    rootId.asIri.map(iri => contextValue.contextObj deepMerge JsonObject(keywords.id -> iri.asJson)) match {
      case Some(frame) =>
        api
          .frame(input, frame.asJson)
          .map { compacted =>
            CompactedJsonLd(rootId, contextValue, compacted.remove(keywords.context))
          }
      case _           => apply(rootId, contextValue, input)
    }

  /**
    * Unsafely constructs a [[CompactedJsonLd]].
    *
    * @param rootId
    *   the root id
    * @param contextValue
    *   the context used in order to build the ''compacted'' document
    * @param compacted
    *   the already compacted document
    */
  final def unsafe(rootId: IriOrBNode, contextValue: ContextValue, compacted: JsonObject): CompactedJsonLd =
    CompactedJsonLd(rootId, contextValue, compacted)

  object Database {
    implicit final val compactedEncoder: Encoder[CompactedJsonLd] = Encoder.instance(_.json)
    implicit final val compactedDecoder: Decoder[CompactedJsonLd] =
      Decoder.instance { hc =>
        for {
          id  <- hc.up.get[Iri]("id")
          obj <- hc.value.asObject.toRight(DecodingFailure("Expected Json Object", hc.history))
        } yield CompactedJsonLd.unsafe(id, hc.value.topContextValueOrEmpty, obj.remove(keywords.context))
      }
  }

}
