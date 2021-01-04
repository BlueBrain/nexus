package ch.epfl.bluebrain.nexus.delta.sdk.http

import akka.http.scaladsl.model.{HttpRequest, StatusCode, StatusCodes}
import io.circe.Json
import io.circe.parser.parse

/**
  * Error that can occur when using a [[HttpClient]]
  */
sealed trait HttpClientError extends Product with Serializable {

  def reason: String

  def details: Option[String] = None

  def detailsJson: Option[Json] = details.flatMap(parse(_).toOption)

  def asString: String =
    s"An error occurred because '$reason'" ++ details.map(d => s"\ndetails '$d'").getOrElse("")

}

object HttpClientError {

  def unsafe(req: HttpRequest, status: StatusCode, body: String): HttpClientError =
    status match {
      case _ if status.isSuccess()       =>
        throw new IllegalArgumentException(s"Successful status code '$status' found, error expected.")
      case code: StatusCodes.ClientError => HttpClientStatusError(req, code, body)
      case code: StatusCodes.ServerError => HttpServerStatusError(req, code, body)
      case _                             => HttpUnexpectedStatusError(req, status, body)
    }

  /**
    * A serialization error when attempting to cast response.
    */
  final case class HttpUnexpectedError(req: HttpRequest, message: String) extends HttpClientError {
    override val reason: String          =
      s"an HTTP response to endpoint '${req.uri}' with method '${req.method}' that should have been successful failed"
    override val details: Option[String] = Some(s"The request failed due to '$message'")
  }

  /**
    * A serialization error when attempting to cast response.
    */
  final case class HttpTimeoutError(req: HttpRequest, message: String) extends HttpClientError {
    override val reason: String          =
      s"an HTTP response to endpoint '${req.uri}' with method '${req.method}' resulted in a timeout"
    override val details: Option[String] = Some(s"The request failed due to '$message'")
  }

  /**
    * A serialization error when attempting to cast response.
    */
  final case class HttpSerializationError(req: HttpRequest, message: String, tpe: String) extends HttpClientError {
    override val reason: String          =
      s"an HTTP response to endpoint '${req.uri}' with method '${req.method}' could not be converted to type '$tpe'"
    override val details: Option[String] = Some(s"the serialization failed due to '$message'")
  }

  /**
    * A Client status error (HTTP status codes 4xx).
    */
  final case class HttpClientStatusError(req: HttpRequest, code: StatusCodes.ClientError, message: String)
      extends HttpClientError {
    override val reason: String          =
      s"an HTTP response to endpoint '${req.uri}' with method '${req.method}' that should have been successful, returned the HTTP status code '$code'"
    override val details: Option[String] = Some(s"The request failed due to '$message'")
  }

  /**
    * A server status error (HTTP status codes 5xx).
    */
  final case class HttpServerStatusError(req: HttpRequest, code: StatusCodes.ServerError, message: String)
      extends HttpClientError {
    override val reason: String          =
      s"an HTTP response to endpoint '${req.uri}' with method '${req.method}' that should have been successful, returned the HTTP status code '$code'"
    override val details: Option[String] = Some(s"The request failed due to '$message'")
  }

  /**
    * Some other response error which is not 4xx nor 5xx
    */
  final case class HttpUnexpectedStatusError(req: HttpRequest, code: StatusCode, message: String)
      extends HttpClientError {
    override val reason: String          =
      s"an HTTP response to endpoint '${req.uri}' with method '${req.method}' that should have been successful, returned the HTTP status code '$code'"
    override val details: Option[String] = Some(s"The request failed due to '$message'")
  }

}
