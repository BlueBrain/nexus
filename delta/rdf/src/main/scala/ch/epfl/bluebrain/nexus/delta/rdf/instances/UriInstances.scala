package ch.epfl.bluebrain.nexus.delta.rdf.instances

import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoder
import io.circe.{Decoder, Encoder}

import scala.util.Try

trait UriInstances {
  implicit final val uriDecoder: Decoder[Uri]             = Decoder.decodeString.emapTry(s => Try(Uri(s)))
  implicit final val uriEncoder: Encoder[Uri]             = Encoder.encodeString.contramap(_.toString())
  implicit final val uriJsonLdDecoder: JsonLdDecoder[Uri] =
    _.getValue(str => Try(Uri(str)).toOption.filter(_.isAbsolute))

  implicit final val uriPathDecoder: Decoder[Uri.Path]             = Decoder.decodeString.emapTry(s => Try(Uri.Path(s)))
  implicit final val uriPathEncoder: Encoder[Uri.Path]             = Encoder.encodeString.contramap(_.toString())
  implicit final val uriPathJsonLdDecoder: JsonLdDecoder[Uri.Path] =
    _.getValue(str => Try(Uri.Path(str)).toOption)
}

object UriInstances extends UriInstances
