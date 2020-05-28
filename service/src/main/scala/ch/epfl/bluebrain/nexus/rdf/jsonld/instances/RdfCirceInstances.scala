package ch.epfl.bluebrain.nexus.rdf.jsonld.instances

import ch.epfl.bluebrain.nexus.rdf.{GraphDecoder, GraphEncoder, Iri}
import ch.epfl.bluebrain.nexus.rdf.Iri.{AbsoluteIri, Path, RelativeIri, Url, Urn}
import io.circe.{Decoder, Encoder, Json}
import io.circe.parser._
import cats.implicits._

trait RdfCirceInstances {
  implicit final val absoluteIriEncoder: Encoder[AbsoluteIri] = Encoder.encodeString.contramap(_.asString)
  implicit final val absoluteIriDecoder: Decoder[AbsoluteIri] = Decoder.decodeString.emap(Iri.absolute)

  implicit final val iriPathEncoder: Encoder[Path] = Encoder.encodeString.contramap(_.asString)
  implicit final val iriPathDecoder: Decoder[Path] = Decoder.decodeString.emap(Path.apply)

  implicit final val iriEncoder: Encoder[Iri] = Encoder.encodeString.contramap(_.asString)
  implicit final val iriDecoder: Decoder[Iri] = Decoder.decodeString.emap(Iri.apply)

  implicit final def urlEncoder(implicit E: Encoder[AbsoluteIri]): Encoder[Url] = E.contramap(identity)
  implicit final val urlDecoder: Decoder[Url]                                   = Decoder.decodeString.emap(Url.apply)

  implicit final def urnEncoder(implicit E: Encoder[AbsoluteIri]): Encoder[Urn] = E.contramap(identity)
  implicit final val urnDecoder: Decoder[Urn]                                   = Decoder.decodeString.emap(Urn.apply)

  implicit final val relativeIriEncoder: Encoder[RelativeIri] = Encoder.encodeString.contramap(_.asString)
  implicit final val relativeIriDecoder: Decoder[RelativeIri] = Decoder.decodeString.emap(Iri.relative)

  implicit final val jsonGraphEncoder: GraphEncoder[Json] = GraphEncoder.graphEncodeString.contramap(_.noSpaces)
  implicit final val jsonGraphDecoder: GraphDecoder[Json] =
    GraphDecoder.graphDecodeString.emap(str => parse(str).leftMap(_.message))
}

object RdfCirceInstances extends RdfCirceInstances
