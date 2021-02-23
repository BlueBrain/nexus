package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.BNode
import ch.epfl.bluebrain.nexus.delta.rdf.RdfError
import ch.epfl.bluebrain.nexus.delta.rdf.RdfError.ConversionError
import ch.epfl.bluebrain.nexus.delta.rdf.graph.{Dot, Graph, NTriples}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.{JsonLdApi, JsonLdOptions}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.{CompactedJsonLd, ExpandedJsonLd}
import io.circe.JsonObject
import monix.bio.IO

/**
  * An elasticsearch raw query result
  */
final case class RawResult(value: JsonObject)

object RawResult {
  implicit val queryResultJsonLdEncoder: JsonLdEncoder[RawResult] = new JsonLdEncoder[RawResult] {

    override def context(value: RawResult): ContextValue = ContextValue.empty

    override def expand(
        value: RawResult
    )(implicit opts: JsonLdOptions, api: JsonLdApi, rcr: RemoteContextResolution): IO[RdfError, ExpandedJsonLd] =
      IO.raiseError(ConversionError("An ElasticSearch response cannot be converted to expanded Json-LD", "expanding"))

    override def dot(
        value: RawResult
    )(implicit opts: JsonLdOptions, api: JsonLdApi, rcr: RemoteContextResolution): IO[RdfError, Dot] =
      IO.raiseError(ConversionError("An ElasticSearch response cannot be converted to Dot", "to dot"))

    override def ntriples(
        value: RawResult
    )(implicit opts: JsonLdOptions, api: JsonLdApi, rcr: RemoteContextResolution): IO[RdfError, NTriples] =
      IO.raiseError(ConversionError("An ElasticSearch response cannot be converted to N-Triples", "to ntriples"))

    override def graph(
        value: RawResult
    )(implicit opts: JsonLdOptions, api: JsonLdApi, rcr: RemoteContextResolution): IO[RdfError, Graph] =
      IO.raiseError(ConversionError("An ElasticSearch response cannot be converted to Graph", "to graph"))

    override def compact(
        value: RawResult
    )(implicit opts: JsonLdOptions, api: JsonLdApi, rcr: RemoteContextResolution): IO[RdfError, CompactedJsonLd] =
      IO.pure(CompactedJsonLd.unsafe(BNode.random, ContextValue.empty, value.value))
  }
}
