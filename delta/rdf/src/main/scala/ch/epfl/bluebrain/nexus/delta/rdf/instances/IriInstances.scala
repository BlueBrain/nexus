package ch.epfl.bluebrain.nexus.delta.rdf.instances

import io.circe.{Decoder, Encoder}
import org.apache.jena.iri.IRI
import ch.epfl.bluebrain.nexus.delta.rdf.syntax._

trait IriInstances {

  implicit final val iriDecoder: Decoder[IRI] = Decoder.decodeString.emap(_.toIri)
  implicit final val iriEncoder: Encoder[IRI] = Encoder.encodeString.contramap(_.toString)
}
