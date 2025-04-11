package ch.epfl.bluebrain.nexus.delta.sdk.projects.model

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import io.circe.{Decoder, Encoder}

/**
  * Holds the project base Iri
  */
final case class ProjectBase(iri: Iri) extends AnyVal {
  override def toString: String = iri.toString
}

object ProjectBase {

  implicit final val projectBaseEncoder: Encoder[ProjectBase] = Encoder.encodeString.contramap(_.iri.toString)
  implicit final val projectBaseDecoder: Decoder[ProjectBase] = Iri.iriDecoder.map(new ProjectBase(_))
}
