package ch.epfl.bluebrain.nexus.delta.sdk.syntax

import akka.http.scaladsl.model.{HttpHeader, StatusCode}
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.HttpResponseFields

trait HttpResponseFieldsSyntax {
  implicit final def httpResponseFieldsSyntax[A](value: A): HttpResponseFieldsOps[A] = new HttpResponseFieldsOps(value)
}

final class HttpResponseFieldsOps[A](private val value: A) extends AnyVal {

  /**
    * @return the HTTP status code extracted from the current value using the [[HttpResponseFields]]
    */
  def status(implicit responseFields: HttpResponseFields[A]): StatusCode =
    responseFields.statusFrom(value)

  /**
    * @return the HTTP headers extracted from the current value using the [[HttpResponseFields]]
    */
  def headers(implicit responseFields: HttpResponseFields[A]): Seq[HttpHeader] =
    responseFields.headersFrom(value)
}
