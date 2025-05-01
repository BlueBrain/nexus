package ch.epfl.bluebrain.nexus.delta.rdf.instances

import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoder
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import io.circe.{Decoder, Encoder}
import org.http4s.Uri

trait UriInstances {

  implicit val uriEncoder: Encoder[Uri] = org.http4s.circe.encodeUri
  implicit val uriDecoder: Decoder[Uri] = org.http4s.circe.decodeUri

  implicit final val uriJsonLdEncoder: JsonLdEncoder[Uri] = JsonLdEncoder.computeFromCirce(ContextValue.empty)
  implicit final val uriJsonLdDecoder: JsonLdDecoder[Uri] =
    _.getValue(str => Uri.fromString(str).toOption.filter { u => u.path.isEmpty || u.path.absolute })

  implicit final val uriPathDecoder: Decoder[Uri.Path] =
    Decoder.decodeString.map(s => Uri.Path.unsafeFromString(s))
  implicit final val uriPathEncoder: Encoder[Uri.Path] = Encoder.encodeString.contramap(_.toString())
}

object UriInstances extends UriInstances
