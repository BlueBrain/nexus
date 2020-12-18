package ch.epfl.bluebrain.nexus.delta.sdk.circe

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import io.circe.{Decoder, Encoder}
import io.circe.generic.semiauto._

import java.time.Instant

final case class SimpleResource(id: Iri, rev: Long, createdAt: Instant, name: String, age: Int)
object SimpleResource {
  implicit val simpleResourceEncoder: Encoder.AsObject[SimpleResource] = deriveEncoder[SimpleResource]
  implicit val simpleResourceDecoder: Decoder[SimpleResource]          = deriveDecoder[SimpleResource]
}
