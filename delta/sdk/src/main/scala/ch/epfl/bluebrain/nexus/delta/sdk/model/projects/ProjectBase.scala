package ch.epfl.bluebrain.nexus.delta.sdk.model.projects

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import io.circe.{Decoder, Encoder}

/**
  * Holds the project base Iri
  */
final case class ProjectBase private (iri: Iri) extends AnyVal {
  override def toString: String = iri.toString
}

object ProjectBase {

  /**
    * Unsafely construct a [[ProjectBase]] from an arbitrary [[Iri]]
    */
  final def unsafe(iri: Iri): ProjectBase = new ProjectBase(iri)

  implicit final val projectBaseEncoder: Encoder[ProjectBase] = Encoder.encodeString.contramap(_.iri.toString)
  implicit final val projectBaseDecoder: Decoder[ProjectBase] = Iri.iriDecoder.map(new ProjectBase(_))
}
