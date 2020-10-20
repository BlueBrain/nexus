package ch.epfl.bluebrain.nexus.delta.rdf.jsonld

import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.{BNode, Iri}
import ch.epfl.bluebrain.nexus.delta.rdf.RdfError.UnexpectedJsonLd
import ch.epfl.bluebrain.nexus.delta.rdf.graph.{Dot, NTriples}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.{JsonLdApi, JsonLdOptions}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.{IriOrBNode, RdfError}
import io.circe.Encoder
import io.circe.syntax._
import monix.bio.{IO, UIO}

trait JsonLdEncoder[A] {

  /**
    * Converts a value to [[JsonLd]]
    */
  def apply(value: A): IO[RdfError, JsonLd]

  /**
    * The default [[ContextValue]] used when compacting
    */
  def defaultContext: ContextValue

  /**
    * Converts a value of type ''A'' to [[CompactedJsonLd]] format.
    *
    * @param value    the value to be converted into a JSON-LD compacted document
    * @param context  the context value to use. If not provided, ''defaultContext'' will be used
    */
  def compact(value: A, context: ContextValue = defaultContext)(implicit
      options: JsonLdOptions,
      api: JsonLdApi,
      resolution: RemoteContextResolution
  ): IO[RdfError, CompactedJsonLd] =
    for {
      jsonld    <- apply(value)
      compacted <- jsonld.toCompacted(context.contextObj)
    } yield compacted

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
      jsonld   <- apply(value)
      expanded <- jsonld.toExpanded
    } yield expanded

  /**
    * Converts a value of type ''A'' to [[Dot]] format.
    *
    * @param value    the value to be converted to Dot format
    * @param context  the context value to use. If not provided, ''defaultContext'' will be used
    */
  def dot(value: A, context: ContextValue = defaultContext)(implicit
      options: JsonLdOptions,
      api: JsonLdApi,
      resolution: RemoteContextResolution
  ): IO[RdfError, Dot] =
    for {
      jsonld <- apply(value)
      graph  <- jsonld.toGraph
      dot    <- graph.toDot(context.contextObj)
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
      jsonld   <- apply(value)
      graph    <- jsonld.toGraph
      ntriples <- graph.toNTriples
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
  def compactFromCirce[A: Encoder.AsObject](iriContext: Iri): JsonLdEncoder[A] =
    compactFromCirce(randomRootNode, ContextValue(iriContext))

  /**
   * Creates a [[JsonLdEncoder]] from an implicitly available Circe Encoder that turns an ''A'' to a compacted Json-LD.
   *
   * @param id         the rootId
   * @param iriContext the Iri context
   */
  def compactFromCirce[A: Encoder.AsObject](id: IriOrBNode, iriContext: Iri): JsonLdEncoder[A] =
    compactFromCirce((_: A) => id, ContextValue(iriContext))

  /**
    * Creates a [[JsonLdEncoder]] from an implicitly available Circe Encoder that turns an ''A'' to a compacted Json-LD.
    *
    * @param fId        the function to obtain the rootId
    * @param iriContext the Iri context
    */
  def compactFromCirce[A: Encoder.AsObject](fId: A => IriOrBNode, iriContext: Iri): JsonLdEncoder[A] =
    compactFromCirce(fId, ContextValue(iriContext))

  /**
    * Creates a [[JsonLdEncoder]] from an implicitly available Circe Encoder that turns an ''A'' to a compacted Json-LD
    * where ''A'' doesn't have a rootId
    *
   * @param context the context
    */
  def compactFromCirce[A: Encoder.AsObject](context: ContextValue): JsonLdEncoder[A] =
    compactFromCirce(randomRootNode, context)

  /**
    * Creates a [[JsonLdEncoder]] from an implicitly available Circe Encoder that turns an ''A'' to a compacted Json-LD.
    *
    * @param fId     the function to obtain the rootId
    * @param context the context
    */
  def compactFromCirce[A: Encoder.AsObject](fId: A => IriOrBNode, context: ContextValue): JsonLdEncoder[A] =
    new JsonLdEncoder[A] {
      override def apply(value: A): IO[RdfError, JsonLd] =
        JsonLd.compactedUnsafe(value.asJsonObject, defaultContext, fId(value)).pure[UIO]

      override val defaultContext: ContextValue = context
    }

  /**
    * Creates an encoder composing two different encoders and a decomposition function ''f''.
    *
    * The root IriOrBNode used to build the resulting [[JsonLd]] is picked from the ''f''.
    *
    * The decomposed ''A'' and ''B'' are resolved using the corresponding encoders and their results are merged.
    * If there are keys present in both the resulting encoding of ''A'' and ''B'', the keys will be overiden with the
    * values of ''B''.
    *
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
      def apply(value: C): IO[RdfError, JsonLd] = {
        val (a, b, rootId) = f(value)
        val jsonLdResult   = (A.apply(a), B.apply(b)).mapN {
          case (ExpandedJsonLd(aObj, _), ExpandedJsonLd(bObj, _))               =>
            Some(ExpandedJsonLd(bObj.deepMerge(aObj), rootId))

          case (CompactedJsonLd(aObj, aCtx, _), CompactedJsonLd(bObj, bCtx, _)) =>
            Some(CompactedJsonLd(aObj.deepMerge(bObj), aCtx.merge(bCtx), rootId))

          case _                                                                => None
        }
        jsonLdResult
          .flatMap(IO.fromOption(_, UnexpectedJsonLd("Both JsonLdEncoders must produce the same JsonLd output format")))
      }

      val defaultContext: ContextValue = A.defaultContext merge B.defaultContext
    }
}
