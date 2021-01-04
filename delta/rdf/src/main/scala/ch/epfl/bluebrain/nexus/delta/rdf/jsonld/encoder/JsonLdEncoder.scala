package ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.BNode
import ch.epfl.bluebrain.nexus.delta.rdf.graph.{Dot, NTriples}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.{JsonLdApi, JsonLdOptions}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.{CompactedJsonLd, ExpandedJsonLd}
import ch.epfl.bluebrain.nexus.delta.rdf.syntax._
import ch.epfl.bluebrain.nexus.delta.rdf.{IriOrBNode, RdfError}
import io.circe.Encoder
import io.circe.syntax._
import monix.bio.{IO, UIO}

trait JsonLdEncoder[A] {

  /**
    * The context for the passed value
    */
  def context(value: A): ContextValue

  /**
    * Converts a value of type ''A'' to [[ExpandedJsonLd]] format.
    *
    * @param value the value to be converted into a JSON-LD expanded document
    */
  def expand(
      value: A
  )(implicit opts: JsonLdOptions, api: JsonLdApi, rcr: RemoteContextResolution): IO[RdfError, ExpandedJsonLd]

  /**
    * Converts a value to [[CompactedJsonLd]]
    * @param value    the value to be converted into a JSON-LD compacted document
    */
  def compact(
      value: A
  )(implicit opts: JsonLdOptions, api: JsonLdApi, rcr: RemoteContextResolution): IO[RdfError, CompactedJsonLd]

  /**
    * Converts a value of type ''A'' to [[Dot]] format.
    *
    * @param value    the value to be converted to Dot format
    */
  def dot(
      value: A
  )(implicit opts: JsonLdOptions, api: JsonLdApi, rcr: RemoteContextResolution): IO[RdfError, Dot] =
    for {
      expanded <- expand(value)
      graph    <- IO.fromEither(expanded.toGraph)
      dot      <- graph.toDot(context(value))
    } yield dot

  /**
    * Converts a value of type ''A'' to [[NTriples]] format.
    *
    * @param value the value to be converted to n-triples format
    */
  def ntriples(
      value: A
  )(implicit opts: JsonLdOptions, api: JsonLdApi, rcr: RemoteContextResolution): IO[RdfError, NTriples] =
    for {
      expanded <- expand(value)
      graph    <- IO.fromEither(expanded.toGraph)
      ntriples <- IO.fromEither(graph.toNTriples)
    } yield ntriples

}

object JsonLdEncoder {

  private def randomRootNode[A]: A => BNode = (_: A) => BNode.random

  /**
    * Creates a [[JsonLdEncoder]] using the available Circe Encoder to convert ''A'' to Json
    * and uses the result as the already compacted form.
    *
    * @param context the context
    */
  def compactedFromCirce[A: Encoder.AsObject](context: ContextValue): JsonLdEncoder[A] =
    compactedFromCirce(randomRootNode, context)

  /**
    * Creates a [[JsonLdEncoder]] using the available Circe Encoder to convert ''A'' to Json
    * and uses the result as the already compacted form.
    *
    * @param id  the rootId
    * @param ctx the context
    */
  def compactedFromCirce[A: Encoder.AsObject](id: IriOrBNode, ctx: ContextValue): JsonLdEncoder[A] =
    compactedFromCirce((_: A) => id, ctx)

  /**
    * Creates a [[JsonLdEncoder]] using the available Circe Encoder to convert ''A'' to Json
    * and uses the result as the already compacted form.
    *
    * @param fId the function to obtain the rootId
    * @param ctx the context
    */
  def compactedFromCirce[A: Encoder.AsObject](fId: A => IriOrBNode, ctx: ContextValue): JsonLdEncoder[A] =
    new JsonLdEncoder[A] {

      override def compact(
          value: A
      )(implicit opts: JsonLdOptions, api: JsonLdApi, rcr: RemoteContextResolution): IO[RdfError, CompactedJsonLd] =
        UIO.pure(CompactedJsonLd.unsafe(fId(value), ctx, value.asJsonObject))

      override def expand(
          value: A
      )(implicit opts: JsonLdOptions, api: JsonLdApi, rcr: RemoteContextResolution): IO[RdfError, ExpandedJsonLd] =
        for {
          compacted <- compact(value)
          expanded  <- compacted.toExpanded
        } yield expanded

      override def context(value: A): ContextValue = ctx
    }

  /**
    * Creates a [[JsonLdEncoder]] from an implicitly available Circe Encoder that turns an ''A'' to Json
    * and computes the compacted and expanded form from it.
    *
    * @param context the context
    */
  def computeFromCirce[A: Encoder](context: ContextValue): JsonLdEncoder[A] =
    computeFromCirce(randomRootNode, context)

  /**
    * Creates a [[JsonLdEncoder]] from an implicitly available Circe Encoder that turns an ''A'' to to Json
    * and computes the compacted and expanded form from it.
    *
    * @param id  the rootId
    * @param ctx the context
    */
  def computeFromCirce[A: Encoder](id: IriOrBNode, ctx: ContextValue): JsonLdEncoder[A] =
    computeFromCirce((_: A) => id, ctx)

  /**
    * Creates a [[JsonLdEncoder]] from an implicitly available Circe Encoder that turns an ''A'' to Json
    * and computes the compacted and expanded form from it.
    *
    * @param fId the function to obtain the rootId
    * @param ctx the context
    */
  def computeFromCirce[A: Encoder](fId: A => IriOrBNode, ctx: ContextValue): JsonLdEncoder[A] =
    new JsonLdEncoder[A] {

      override def compact(
          value: A
      )(implicit opts: JsonLdOptions, api: JsonLdApi, rcr: RemoteContextResolution): IO[RdfError, CompactedJsonLd] =
        for {
          expanded  <- expand(value)
          compacted <- expanded.toCompacted(context(value))
        } yield compacted

      override def expand(
          value: A
      )(implicit opts: JsonLdOptions, api: JsonLdApi, rcr: RemoteContextResolution): IO[RdfError, ExpandedJsonLd] = {
        val json = value.asJson.addContext(context(value).contextObj)
        ExpandedJsonLd(json).map {
          case expanded if fId(value).isBNode && expanded.rootId.isIri => expanded
          case expanded                                                => expanded.replaceId(fId(value))
        }
      }

      override def context(value: A): ContextValue = value.asJson.topContextValueOrEmpty merge ctx
    }

  implicit val jsonLdEncoderUnit: JsonLdEncoder[Unit] = new JsonLdEncoder[Unit] {
    override def compact(
        value: Unit
    )(implicit opts: JsonLdOptions, api: JsonLdApi, rcr: RemoteContextResolution): IO[RdfError, CompactedJsonLd] =
      IO.pure(CompactedJsonLd.empty)

    override def expand(
        value: Unit
    )(implicit opts: JsonLdOptions, api: JsonLdApi, rcr: RemoteContextResolution): IO[RdfError, ExpandedJsonLd] =
      IO.pure(ExpandedJsonLd.empty)

    override def context(value: Unit): ContextValue = ContextValue.empty
  }
}
