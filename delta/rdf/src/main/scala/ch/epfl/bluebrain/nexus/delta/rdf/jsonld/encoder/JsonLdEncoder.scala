package ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder

import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.{BNode, Iri}
import ch.epfl.bluebrain.nexus.delta.rdf.graph.{Dot, NTriples}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.{JsonLdApi, JsonLdOptions}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.{CompactedJsonLd, ExpandedJsonLd, JsonLd}
import ch.epfl.bluebrain.nexus.delta.rdf.{IriOrBNode, RdfError}
import io.circe.Encoder
import io.circe.syntax._
import monix.bio.{IO, UIO}

trait JsonLdEncoder[A] {

  /**
    * Converts a value to [[CompactedJsonLd]]
    * @param value    the value to be converted into a JSON-LD compacted document
    */
  def compact(value: A): IO[RdfError, CompactedJsonLd]

  /**
    * The context for the passed value
    */
  def context(value: A): ContextValue

  /**
    * Converts a value of type ''A'' to [[ExpandedJsonLd]] format.
    *
    * @param value the value to be converted into a JSON-LD expanded document
    */
  def expand(value: A)(implicit
      options: JsonLdOptions,
      api: JsonLdApi,
      resolution: RemoteContextResolution
  ): IO[RdfError, ExpandedJsonLd] =
    for {
      compacted <- compact(value)
      expanded  <- compacted.toExpanded
    } yield expanded

  /**
    * Converts a value of type ''A'' to [[Dot]] format.
    *
    * @param value    the value to be converted to Dot format
    */
  def dot(value: A)(implicit
      options: JsonLdOptions,
      api: JsonLdApi,
      resolution: RemoteContextResolution
  ): IO[RdfError, Dot] =
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
  def ntriples(value: A)(implicit
      options: JsonLdOptions,
      api: JsonLdApi,
      resolution: RemoteContextResolution
  ): IO[RdfError, NTriples] =
    for {
      expanded <- expand(value)
      graph    <- IO.fromEither(expanded.toGraph)
      ntriples <- IO.fromEither(graph.toNTriples)
    } yield ntriples

}

object JsonLdEncoder {

  private def randomRootNode[A]: A => BNode = (_: A) => BNode.random

  /**
    * Creates a [[JsonLdEncoder]] from an implicitly available Circe Encoder that turns an ''A'' to a compacted Json-LD.
    * where ''A'' doesn't have a rootId
    *
    * @param iriContext the Iri context
    */
  def fromCirce[A: Encoder.AsObject](iriContext: Iri): JsonLdEncoder[A] =
    fromCirce(randomRootNode, ContextValue(iriContext))

  /**
    * Creates a [[JsonLdEncoder]] from an implicitly available Circe Encoder that turns an ''A'' to a compacted Json-LD.
    *
    * @param id         the rootId
    * @param iriContext the Iri context
    */
  def fromCirce[A: Encoder.AsObject](id: IriOrBNode, iriContext: Iri): JsonLdEncoder[A] =
    fromCirce((_: A) => id, ContextValue(iriContext))

  /**
    * Creates a [[JsonLdEncoder]] from an implicitly available Circe Encoder that turns an ''A'' to a compacted Json-LD.
    *
    * @param fId        the function to obtain the rootId
    * @param iriContext the Iri context
    */
  def fromCirce[A: Encoder.AsObject](fId: A => IriOrBNode, iriContext: Iri): JsonLdEncoder[A] =
    fromCirce(fId, ContextValue(iriContext))

  /**
    * Creates a [[JsonLdEncoder]] from an implicitly available Circe Encoder that turns an ''A'' to a compacted Json-LD
    * where ''A'' doesn't have a rootId
    *
    * @param context the context
    */
  def fromCirce[A: Encoder.AsObject](context: ContextValue): JsonLdEncoder[A] =
    fromCirce(randomRootNode, context)

  /**
    * Creates a [[JsonLdEncoder]] from an implicitly available Circe Encoder that turns an ''A'' to a compacted Json-LD.
    *
    * @param fId     the function to obtain the rootId
    * @param ctx the context
    */
  def fromCirce[A: Encoder.AsObject](fId: A => IriOrBNode, ctx: ContextValue): JsonLdEncoder[A] =
    new JsonLdEncoder[A] {
      override def compact(value: A): IO[RdfError, CompactedJsonLd] =
        UIO.pure(JsonLd.compactedUnsafe(value.asJsonObject, ctx, fId(value)))

      override def context(value: A): ContextValue = ctx
    }

  /**
    * Creates an encoder composing two different encoders and a decomposition function ''f''.
    *
    * The root IriOrBNode used to build the resulting [[JsonLd]] is picked from the ''f''.
    *
    * The decomposed ''A'' and ''B'' are resolved using the corresponding encoders and their results are merged.
    * If there are keys present in both the resulting encoding of ''A'' and ''B'', the keys will be overridden with the
    * values of ''B''.
    *
    * @param f  the function to decomposed the value of the target encoder into values the passed encoders and its IriOrBNode
    * @tparam A the generic type for values of the first passed encoder
    * @tparam B the generic type for values of the second passed encoder
    * @tparam C the generic type for values of the target encoder
    */
  def compose[A, B, C](
      f: C => (A, B, IriOrBNode)
  )(implicit A: JsonLdEncoder[A], B: JsonLdEncoder[B]): JsonLdEncoder[C] =
    new JsonLdEncoder[C] {
      override def compact(value: C): IO[RdfError, CompactedJsonLd] = {
        val (a, b, rootId) = f(value)
        (A.compact(a), B.compact(b)).mapN { case (CompactedJsonLd(aObj, aCtx, _), CompactedJsonLd(bObj, bCtx, _)) =>
          CompactedJsonLd(aObj.deepMerge(bObj), aCtx.merge(bCtx), rootId)
        }
      }

      override def expand(value: C)(implicit
          options: JsonLdOptions,
          api: JsonLdApi,
          resolution: RemoteContextResolution
      ): IO[RdfError, ExpandedJsonLd] = {
        val (a, b, rootId) = f(value)
        (A.expand(a), B.expand(b)).mapN { case (ExpandedJsonLd(aObj, _), ExpandedJsonLd(bObj, _)) =>
          ExpandedJsonLd(bObj.deepMerge(aObj), rootId)
        }
      }

      override def context(value: C): ContextValue = {
        val (a, b, _) = f(value)
        A.context(a).merge(B.context(b))
      }
    }

  implicit val jsonLdEncoderUnit: JsonLdEncoder[Unit] = new JsonLdEncoder[Unit] {
    override def compact(value: Unit): IO[RdfError, CompactedJsonLd] = IO.pure(CompactedJsonLd.empty)
    override def context(value: Unit): ContextValue                  = ContextValue.empty
  }
}
