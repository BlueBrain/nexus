package ch.epfl.bluebrain.nexus.delta.rdf.syntax

import ch.epfl.bluebrain.nexus.delta.rdf._
import ch.epfl.bluebrain.nexus.delta.rdf.graph.{Dot, Graph, NTriples}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.{JsonLdApi, JsonLdOptions}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.{CompactedJsonLd, ExpandedJsonLd}
import monix.bio.IO

trait JsonLdEncoderSyntax {
  implicit final def jsonLdEncoderSyntax[A: JsonLdEncoder](a: A): JsonLdEncoderOpts[A] = new JsonLdEncoderOpts(a)
}

final class JsonLdEncoderOpts[A](private val value: A)(implicit encoder: JsonLdEncoder[A]) {

  /**
    * Converts a value of type ''A'' to [[CompactedJsonLd]] format using the ''defaultContext'' available on the encoder.
    */
  def toCompactedJsonLd(implicit
      options: JsonLdOptions,
      api: JsonLdApi,
      resolution: RemoteContextResolution
  ): IO[RdfError, CompactedJsonLd] =
    encoder.compact(value)

  /**
    * Converts a value of type ''A'' to [[ExpandedJsonLd]] format.
    */
  def toExpandedJsonLd(implicit
      options: JsonLdOptions,
      api: JsonLdApi,
      resolution: RemoteContextResolution
  ): IO[RdfError, ExpandedJsonLd] = encoder.expand(value)

  /**
    * Converts a value of type ''A'' to [[Dot]] format using the ''defaultContext'' available on the encoder.
    */
  def toDot(implicit
      options: JsonLdOptions,
      api: JsonLdApi,
      resolution: RemoteContextResolution
  ): IO[RdfError, Dot] = encoder.dot(value)

  /**
    * Converts a value of type ''A'' to [[NTriples]] format.
    */
  def toNTriples(implicit
      options: JsonLdOptions,
      api: JsonLdApi,
      resolution: RemoteContextResolution
  ): IO[RdfError, NTriples] = encoder.ntriples(value)

  /**
    * Converts a value of type ''A'' to [[Graph]] format.
    */
  def toGraph(implicit
      options: JsonLdOptions,
      api: JsonLdApi,
      resolution: RemoteContextResolution
  ): IO[RdfError, Graph] = encoder.graph(value)
}
