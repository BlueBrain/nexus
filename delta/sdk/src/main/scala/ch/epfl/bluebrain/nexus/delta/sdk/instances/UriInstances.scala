package ch.epfl.bluebrain.nexus.delta.sdk.instances

import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoder
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import io.circe.{Decoder, Encoder}
import org.http4s.Uri
import org.http4s.circe.CirceInstances

trait UriInstances extends CirceInstances {

  implicit final val http4sUriJsonLdEncoder: JsonLdEncoder[Uri] = JsonLdEncoder.computeFromCirce(ContextValue.empty)
  implicit final val http4sUriJsonLdDecoder: JsonLdDecoder[Uri] =
    _.getValue(str => Uri.fromString(str).toOption.filter { u => u.path.isEmpty || u.path.absolute })

  implicit final val http4sUriPathDecoder: Decoder[Uri.Path] =
    Decoder.decodeString.map(s => Uri.Path.unsafeFromString(s))
  implicit final val http4sUriPathEncoder: Encoder[Uri.Path] = Encoder.encodeString.contramap(_.toString())

}
