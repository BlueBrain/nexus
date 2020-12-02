package ch.epfl.bluebrain.nexus.delta.rdf.syntax

import ch.epfl.bluebrain.nexus.delta.rdf._
import ch.epfl.bluebrain.nexus.delta.rdf.graph.{Dot, NTriples}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.{JsonLdApi, JsonLdOptions}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.{CompactedJsonLd, ExpandedJsonLd}
import monix.bio.IO

trait JsonLdEncoderSyntax {
  implicit final def jsonLdEncoderSyntax[A](a: A): JsonLdEncoderOpts[A] = new JsonLdEncoderOpts(a)
}

final class JsonLdEncoderOpts[A](private val value: A) extends AnyVal {

  /**
    * Converts a value of type ''A'' to [[CompactedJsonLd]] format using the ''defaultContext'' available on the encoder.
    */
  def toCompactedJsonLd(implicit
      encoder: JsonLdEncoder[A],
      options: JsonLdOptions,
      api: JsonLdApi,
      resolution: RemoteContextResolution
  ): IO[RdfError, CompactedJsonLd] =
    encoder.compact(value)

  /**
    * Converts a value of type ''A'' to [[ExpandedJsonLd]] format.
    */
  def toExpandedJsonLd(implicit
      encoder: JsonLdEncoder[A],
      options: JsonLdOptions,
      api: JsonLdApi,
      resolution: RemoteContextResolution
  ): IO[RdfError, ExpandedJsonLd] = encoder.expand(value)

  /**
    * Converts a value of type ''A'' to [[Dot]] format using the ''defaultContext'' available on the encoder.
    */
  def toDot(implicit
      encoder: JsonLdEncoder[A],
      options: JsonLdOptions,
      api: JsonLdApi,
      resolution: RemoteContextResolution
  ): IO[RdfError, Dot] = encoder.dot(value)

  /**
    * Converts a value of type ''A'' to [[NTriples]] format.
    */
  def toNTriples(implicit
      encoder: JsonLdEncoder[A],
      options: JsonLdOptions,
      api: JsonLdApi,
      resolution: RemoteContextResolution
  ): IO[RdfError, NTriples] = encoder.ntriples(value)
}
