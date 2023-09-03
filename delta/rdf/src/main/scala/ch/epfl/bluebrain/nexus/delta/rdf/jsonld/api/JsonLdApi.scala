package ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api

import ch.epfl.bluebrain.nexus.delta.rdf.{ExplainResult, RdfError}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, JsonLdContext, RemoteContextResolution}
import io.circe.{Json, JsonObject}
import monix.bio.IO
import org.apache.jena.sparql.core.DatasetGraph

/**
  * Json-LD high level API as defined in the ''JsonLdProcessor'' interface of the Json-LD spec.
  *
  * Interface definition for compact, expand, frame, toRdf, fromRdf:
  * https://www.w3.org/TR/json-ld11-api/#the-jsonldprocessor-interface Interface definition for frame:
  * https://www.w3.org/TR/json-ld11-framing/#jsonldprocessor
  */
trait JsonLdApi {
  private[rdf] def compact(
      input: Json,
      ctx: ContextValue
  )(implicit opts: JsonLdOptions, rcr: RemoteContextResolution): IO[RdfError, JsonObject]

  private[rdf] def expand(
      input: Json
  )(implicit opts: JsonLdOptions, rcr: RemoteContextResolution): IO[RdfError, Seq[JsonObject]]

  /**
    * Performs the expand operation and provides details on its execution
    */
  private[rdf] def explainExpand(
      input: Json
  )(implicit opts: JsonLdOptions, rcr: RemoteContextResolution): IO[RdfError, ExplainResult[Seq[JsonObject]]]

  private[rdf] def frame(
      input: Json,
      frame: Json
  )(implicit opts: JsonLdOptions, rcr: RemoteContextResolution): IO[RdfError, JsonObject]

  private[rdf] def toRdf(input: Json)(implicit opts: JsonLdOptions): Either[RdfError, DatasetGraph]

  private[rdf] def fromRdf(input: DatasetGraph)(implicit opts: JsonLdOptions): Either[RdfError, Seq[JsonObject]]

  private[rdf] def context(
      value: ContextValue
  )(implicit opts: JsonLdOptions, rcr: RemoteContextResolution): IO[RdfError, JsonLdContext]

}
