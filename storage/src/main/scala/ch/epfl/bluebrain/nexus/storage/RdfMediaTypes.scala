package ch.epfl.bluebrain.nexus.commons.http

import akka.http.scaladsl.model.HttpCharsets._
import akka.http.scaladsl.model.MediaType

/**
  * Collection of media types specific to RDF.
  */
object RdfMediaTypes {
  final val `application/ld+json`: MediaType.WithFixedCharset =
    MediaType.applicationWithFixedCharset("ld+json", `UTF-8`, "jsonld")

}
