package ch.epfl.bluebrain.nexus.delta.sdk.utils

import akka.http.scaladsl.model.headers.Accept
import akka.http.scaladsl.model.{HttpHeader, MediaRanges, MediaType}
import akka.http.scaladsl.server.MediaTypeNegotiator

object HeadersUtils {

  /**
    * Extracts the first mediaType found in the ''Accept'' Http request header that matches the  ''serviceMediaTypes''.
    * If the Accept header does not match any of the service supported ''mediaTypes'', return None
    *
    * @param headers           the headers to be tested (usually the request headers)
    * @param serviceMediaTypes the supported media types
    * @param exactMatch        if true the search is performed without considering wildcards (e.g. '* / *' will not match)
    */
  def findFirst(
      headers: Seq[HttpHeader],
      serviceMediaTypes: Seq[MediaType],
      exactMatch: Boolean = false
  ): Option[MediaType] =
    if (exactMatch)
      headers
        .collectFirst { case accept: Accept =>
          accept
        }
        .flatMap { accept =>
          val rangesToTypes = serviceMediaTypes.map { mt => (mt.toRange, mt) }.toMap
          accept.mediaRanges.find(mr => rangesToTypes.keySet.contains(mr)).map(found => rangesToTypes(found))
        }
    else {
      val ct       = new MediaTypeNegotiator(headers)
      val accepted = if (ct.acceptedMediaRanges.isEmpty) List(MediaRanges.`*/*`) else ct.acceptedMediaRanges
      accepted.foldLeft[Option[MediaType]](None) {
        case (s @ Some(_), _) => s
        case (None, mr)       => serviceMediaTypes.find(mt => mr.matches(mt))
      }
    }

  /**
    * Extracts the mediaTypes found in the ''Accept'' Http request and tries to match it to the passed ''mediaType''
    */
  def matches(headers: Seq[HttpHeader], mediaType: MediaType): Boolean =
    findFirst(headers, Seq(mediaType)).nonEmpty

}
