package ch.epfl.bluebrain.nexus.delta.routes.models

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.RdfError
import ch.epfl.bluebrain.nexus.delta.rdf.RdfError.UnexpectedJsonLd
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.{CompactedJsonLd, JsonLd, JsonLdEncoder}
import ch.epfl.bluebrain.nexus.delta.syntax._
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

      override def compact(value: JsonSource): IO[RdfError, CompactedJsonLd] =
        IO.fromEither(toObj(value.value)).map(obj => JsonLd.compactedUnsafe(obj, value.context, value.id))

      override def context(value: JsonSource): ContextValue = value.context
    }
}
