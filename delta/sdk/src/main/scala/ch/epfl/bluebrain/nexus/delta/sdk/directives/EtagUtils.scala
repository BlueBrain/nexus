package ch.epfl.bluebrain.nexus.delta.sdk.directives

import akka.http.scaladsl.model.MediaType
import akka.http.scaladsl.model.headers.{EntityTag, HttpEncoding}
import ch.epfl.bluebrain.nexus.delta.kernel.MD5
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.JsonLdFormat

object EtagUtils {

  private[directives] def computeRawValue(
      value: String,
      mediaType: MediaType,
      jsonldFormat: Option[JsonLdFormat],
      encoding: HttpEncoding
  ) = s"${value}_${mediaType}${jsonldFormat.map { f => s"_$f" }.getOrElse("")}_$encoding"

  def compute(value: String, mediaType: MediaType, jsonldFormat: Option[JsonLdFormat], encoding: HttpEncoding): EntityTag = {
    val rawEtag = computeRawValue(value, mediaType, jsonldFormat, encoding)
    EntityTag(MD5.hash(rawEtag))
  }

}
