package ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.BNode
import ch.epfl.bluebrain.nexus.delta.rdf.graph.{Dot, Graph, NQuads, NTriples}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.{JsonLdApi, JsonLdOptions}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.{CompactedJsonLd, ExpandedJsonLd}
import ch.epfl.bluebrain.nexus.delta.rdf.syntax.*
import io.circe.syntax.*
import io.circe.{Encoder, Json}

trait JsonLdEncoder[A] {

  /**
    * The context for the passed value
    */
  def context(value: A): ContextValue

  /**
    * Converts a value of type ''A'' to [[ExpandedJsonLd]] format.
    *
    * @param value
    *   the value to be converted into a JSON-LD expanded document
    */
  def expand(
      value: A
  )(implicit opts: JsonLdOptions, api: JsonLdApi, rcr: RemoteContextResolution): IO[ExpandedJsonLd]

  /**
    * Converts a value to [[CompactedJsonLd]]
    * @param value
    *   the value to be converted into a JSON-LD compacted document
    */
  def compact(
      value: A
  )(implicit opts: JsonLdOptions, api: JsonLdApi, rcr: RemoteContextResolution): IO[CompactedJsonLd]

  /**
    * Converts a value of type ''A'' to [[Dot]] format.
    *
    * @param value
    *   the value to be converted to Dot format
    */
  def dot(
      value: A
  )(implicit opts: JsonLdOptions, api: JsonLdApi, rcr: RemoteContextResolution): IO[Dot] =
    for {
      graph <- graph(value)
      dot   <- graph.toDot(context(value))
    } yield dot

  /**
    * Converts a value of type ''A'' to [[NTriples]] format.
    *
    * @param value
    *   the value to be converted to n-triples format
    */
  def ntriples(
      value: A
  )(implicit opts: JsonLdOptions, api: JsonLdApi, rcr: RemoteContextResolution): IO[NTriples] =
    graph(value).flatMap(_.toNTriples)

  /**
    * Converts a value of type ''A'' to [[NQuads]] format.
    *
    * @param value
    *   the value to be converted to n-quads format
    */
  def nquads(
      value: A
  )(implicit opts: JsonLdOptions, api: JsonLdApi, rcr: RemoteContextResolution): IO[NQuads] =
    graph(value).flatMap(_.toNQuads)

  /**
    * Converts a value of type ''A'' to [[Graph]]
    *
    * @param value
    *   the value to be converted to Graph
    */
  def graph(
      value: A
  )(implicit opts: JsonLdOptions, api: JsonLdApi, rcr: RemoteContextResolution): IO[Graph] =
    expand(value).flatMap(_.toGraph)

}

object JsonLdEncoder {

  private def randomRootNode[A]: A => BNode = (_: A) => BNode.random

  /**
    * Creates a [[JsonLdEncoder]] from an implicitly available Circe Encoder that turns an ''A'' to Json and computes
    * the compacted and expanded form from it.
    *
    * @param context
    *   the context
    */
  def computeFromCirce[A: Encoder](context: ContextValue): JsonLdEncoder[A] =
    computeFromCirce(randomRootNode, context)

  /**
    * Creates a [[JsonLdEncoder]] from an implicitly available Circe Encoder that turns an ''A'' to to Json and computes
    * the compacted and expanded form from it.
    *
    * @param id
    *   the rootId
    * @param ctx
    *   the context
    */
  def computeFromCirce[A: Encoder](id: IriOrBNode, ctx: ContextValue): JsonLdEncoder[A] =
    computeFromCirce((_: A) => id, ctx)

  /**
    * Creates a [[JsonLdEncoder]] from an implicitly available Circe Encoder that turns an ''A'' to Json and computes
    * the compacted and expanded form from it.
    *
    * @param fId
    *   the function to obtain the rootId
    * @param ctx
    *   the context
    */
  def computeFromCirce[A: Encoder](fId: A => IriOrBNode, ctx: ContextValue): JsonLdEncoder[A] =
    new JsonLdEncoder[A] {

      override def compact(
          value: A
      )(implicit opts: JsonLdOptions, api: JsonLdApi, rcr: RemoteContextResolution): IO[CompactedJsonLd] =
        for {
          (expanded, context) <- expandAndExtractContext(value)
          compacted           <- expanded.toCompacted(context)
        } yield compacted

      override def expand(
          value: A
      )(implicit opts: JsonLdOptions, api: JsonLdApi, rcr: RemoteContextResolution): IO[ExpandedJsonLd] =
        expandAndExtractContext(value).map(_._1)

      private def expandAndExtractContext(
          value: A
      )(implicit opts: JsonLdOptions, api: JsonLdApi, rcr: RemoteContextResolution) = {
        val json    = value.asJson
        val context = contextFromJson(json)
        ExpandedJsonLd(json.replaceContext(context.contextObj))
          .map {
            case expanded if fId(value).isBNode && expanded.rootId.isIri => expanded
            case expanded                                                => expanded.replaceId(fId(value))
          }
          .map(_ -> context)
      }

      override def context(value: A): ContextValue = contextFromJson(value.asJson)

      private def contextFromJson(json: Json): ContextValue = json.topContextValueOrEmpty merge ctx
    }

  implicit val jsonLdEncoderUnit: JsonLdEncoder[Unit] = new JsonLdEncoder[Unit] {
    override def compact(
        value: Unit
    )(implicit opts: JsonLdOptions, api: JsonLdApi, rcr: RemoteContextResolution): IO[CompactedJsonLd] =
      IO.pure(CompactedJsonLd.empty)

    override def expand(
        value: Unit
    )(implicit opts: JsonLdOptions, api: JsonLdApi, rcr: RemoteContextResolution): IO[ExpandedJsonLd] =
      IO.pure(ExpandedJsonLd.empty)

    override def context(value: Unit): ContextValue = ContextValue.empty
  }
}
