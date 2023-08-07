package ch.epfl.bluebrain.nexus.storage

import akka.http.scaladsl.model.MediaType
import akka.http.scaladsl.model.HttpCharsets.`UTF-8`

object MediaTypes {
  final val `application/ld+json`: MediaType.WithFixedCharset =
    MediaType.applicationWithFixedCharset("ld+json", `UTF-8`, "jsonld")
}
