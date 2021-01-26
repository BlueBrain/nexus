package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client

import akka.http.scaladsl.unmarshalling.FromEntityUnmarshaller
import akka.http.scaladsl.unmarshalling.PredefinedFromEntityUnmarshallers._

/**
  * Information about the deployed service
  *
  * @param name    service name
  * @param version service version
  */
final case class ServiceDescription(name: String, version: String)

object ServiceDescription {
  private val regex = """(buildVersion">)([^<]*)""".r
  private val name  = "blazegraph"

  implicit val serviceDescDecoder: FromEntityUnmarshaller[ServiceDescription] = stringUnmarshaller.map {
    regex.findFirstMatchIn(_).map(_.group(2)) match {
      case None          => throw new IllegalArgumentException(s"'version' not found using regex $regex")
      case Some(version) => ServiceDescription(name, version)
    }
  }

}
