package ch.epfl.bluebrain.nexus.commons.es.client

import io.circe.Decoder

/**
  * Information about the deployed service
  *
  * @param name    service name
  * @param version service version
  */
final case class ServiceDescription(name: String, version: String)

object ServiceDescription {
  implicit val serviceDescDecoder: Decoder[ServiceDescription] = Decoder.instance { hc =>
    for {
      name    <- hc.get[String]("cluster_name")
      version <- hc.downField("version").get[String]("number")
    } yield ServiceDescription(name, version)
  }

}
