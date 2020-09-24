package ch.epfl.bluebrain.nexus.delta.rdf.jsonld

import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.rdf.RdfError
import ch.epfl.bluebrain.nexus.delta.rdf.RdfError.UnexpectedJsonLd
import ch.epfl.bluebrain.nexus.delta.rdf.graph.{Dot, NTriples}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.{JsonLdApi, JsonLdOptions}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextFields, RawJsonLdContext, RemoteContextResolution}
import monix.bio.IO
import org.apache.jena.iri.IRI

trait JsonLdEncoder[A] {

  /**
    * Converts a value to [[JsonLd]]
    */
  def apply(value: A): IO[RdfError, JsonLd]

  /**
    * The default [[RawJsonLdContext]] used when compacting
    */
  def defaultContext: RawJsonLdContext

  /**
    * Converts a value of type ''A'' to [[CompactedJsonLd]] format.
    *
    * @param value    the value to be converted into a JSON-LD compacted document
    * @param context  the context to use. If not provided, ''contextValue'' will be used
    */
  def compact(value: A, context: RawJsonLdContext = defaultContext)(implicit
      options: JsonLdOptions,
      api: JsonLdApi,
      resolution: RemoteContextResolution
  ): IO[RdfError, CompactedJsonLd[RawJsonLdContext]] =
    for {
      jsonld    <- apply(value)
      compacted <- jsonld.toCompacted(context.contextObj, ContextFields.Skip)
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
    * @param context  the context to use. If not provided, ''contextValue'' will be used
    */
  def dot(value: A, context: RawJsonLdContext = defaultContext)(implicit
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

  /**
    * Creates an encoder composing two different encoders and a decomposition function ''f''.
    *
    * The root IRI used to build the resulting [[JsonLd]] is picked from the ''f''.
    *
    * The decomposed ''A'' and ''B'' are resolved using the corresponding encoders and their results are merged.
    * If there are keys present in both the resulting encoding of ''A'' and ''B'', the keys will be overiden with the
    * values of ''B''.
    *
    *
    * @param f  the function to decomposed the value of the target encoder into values the passed encoders and its IRI
    * @tparam A the generic type for values of the first passed encoder
    * @tparam B the generic type for values of the second passed encoder
    * @tparam C the generic type for values of the target encoder
    */
  def compose[A, B, C](f: C => (A, B, IRI))(implicit A: JsonLdEncoder[A], B: JsonLdEncoder[B]): JsonLdEncoder[C] =
    new JsonLdEncoder[C] {
      def apply(value: C): IO[RdfError, JsonLd] = {
        val (a, b, rootId) = f(value)
        val jsonLdResult   = (A.apply(a), B.apply(b)).mapN {
          case (ExpandedJsonLd(aObj, _), ExpandedJsonLd(bObj, _)) =>
            Some(ExpandedJsonLd(bObj.deepMerge(aObj), rootId))

          case (
                CompactedJsonLd(aObj, aCtx @ RawJsonLdContext(_), _, _),
                CompactedJsonLd(bObj, bCtx @ RawJsonLdContext(_), _, _)
              ) =>
            Some(CompactedJsonLd(aObj.deepMerge(bObj), aCtx.merge(bCtx), rootId, ContextFields.Skip))

          case _                                                  => None
        }
        jsonLdResult
          .flatMap(IO.fromOption(_, UnexpectedJsonLd("Both JsonLdEncoders must produce the same JsonLd output format")))
      }

      val defaultContext: RawJsonLdContext = A.defaultContext merge B.defaultContext
    }
}
