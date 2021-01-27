package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client

import io.circe.Decoder

/**
  * Information about the deployed service
  *
  * @param version service version
  */
final case class ServiceDescription(version: String) {

  /**
    * the service name
    */
  val name: String = "elasticsearch"
}

object ServiceDescription {

  implicit val serviceDescDecoder: Decoder[ServiceDescription] = Decoder.instance { hc =>
    hc.downField("version").get[String]("number").map(ServiceDescription(_))
  }

}
