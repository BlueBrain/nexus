package ch.epfl.bluebrain.nexus.storage.client.types

import io.circe.Decoder
import io.circe.generic.semiauto.deriveDecoder

/**
  * Information about the deployed service
  *
  * @param name    service name
  * @param version service version
  */
final case class ServiceDescription(name: String, version: String)

object ServiceDescription {
  // $COVERAGE-OFF$
  implicit val serviceDescDecoder: Decoder[ServiceDescription] = deriveDecoder[ServiceDescription]
  // $COVERAGE-ON$

}
