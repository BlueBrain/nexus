package ch.epfl.bluebrain.nexus.delta.sdk.model.routes

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.RdfError
import ch.epfl.bluebrain.nexus.delta.rdf.RdfError.UnexpectedJsonLd
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.{JsonLdApi, JsonLdOptions}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.{CompactedJsonLd, ExpandedJsonLd}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import io.circe.{Json, JsonObject}
import monix.bio.IO

/**
  * Holds the source payload as the client posted it of a resource as [[Json]]
  *
  * @param value the client payload
  * @param id    the source identifier
  */
final case class JsonSource(value: Json, id: Iri) {
  def context: ContextValue = value.topContextValueOrEmpty
}

object JsonSource {
  implicit val jsonLdEncoderJsonResource: JsonLdEncoder[JsonSource] =
    new JsonLdEncoder[JsonSource] {

      private def toObj(json: Json): Either[RdfError, JsonObject] =
        json.arrayOrObject(
          or = Left(UnexpectedJsonLd("Expected a Json Object or a Json Array")),
          arr =>
            arr.singleEntry
              .flatMap(_.asObject)
              .toRight(UnexpectedJsonLd("Expected Json Array with single Json Object")),
          obj => Right(obj)
        )

      override def compact(
          value: JsonSource
      )(implicit opts: JsonLdOptions, api: JsonLdApi, rcr: RemoteContextResolution): IO[RdfError, CompactedJsonLd] =
        IO.fromEither(toObj(value.value)).map(obj => CompactedJsonLd.unsafe(value.id, value.context, obj))

      override def expand(
          value: JsonSource
      )(implicit opts: JsonLdOptions, api: JsonLdApi, rcr: RemoteContextResolution): IO[RdfError, ExpandedJsonLd] =
        ExpandedJsonLd(value.value).map(_.replaceId(value.id))

      override def context(value: JsonSource): ContextValue = value.context
    }
}
