package ch.epfl.bluebrain.nexus.delta.sdk.http

import akka.http.scaladsl.model.headers.HttpEncoding
import akka.http.scaladsl.model.{HttpHeader, HttpRequest, StatusCode, StatusCodes}
import io.circe.Json
import io.circe.parser.parse

/**
  * Error that can occur when using a [[HttpClient]]
  */
sealed trait HttpClientError extends Exception with Product with Serializable {

  override def fillInStackTrace(): HttpClientError = this

  override def getMessage: String = asString

  def reason: String

  def details: Option[String] = None

  def body: Option[String] = None

  def jsonBody: Option[Json] = body.flatMap(parse(_).toOption)

  def asString: String =
    reason ++ details.fold("")(d => s": $d") ++ jsonBody
      .map(d => s"\nResponse body '${d.spaces2}''")
      .orElse(body.map(d => s"\nResponse body '$d''"))
      .getOrElse("")

  def errorCode: Option[StatusCode]

}

object HttpClientError {

  def apply(
      req: HttpRequest,
      responseStatus: StatusCode,
      responseHeaders: Seq[HttpHeader],
      body: String
  ): HttpClientError =
    responseStatus match {
      case code: StatusCodes.ClientError => HttpClientStatusError(req, code, responseHeaders, body)
      case code: StatusCodes.ServerError => HttpServerStatusError(req, code, body)
      case _                             => HttpUnexpectedStatusError(req, responseStatus, body)
    }

  /**
    * An unexpected error.
    */
  final case class HttpUnexpectedError(req: HttpRequest, message: String) extends HttpClientError {
    override val reason: String          =
      s"an HTTP response to endpoint '${req.uri}' with method '${req.method}' that should have been successful failed"
    override val details: Option[String] = Some(s"the request failed due to '$message'")

    override val errorCode: Option[StatusCode] = None
  }

  /**
    * An error when the requested endpoint host cannot be resolved.
    */
  final case class HttpUnknownHost(req: HttpRequest) extends HttpClientError {
    override val reason: String          =
      s"an HTTP response to endpoint '${req.uri}' with method '${req.method}' failed because the host '${req.uri.authority.host}' cannot be resolved"
    override val details: Option[String] = Some(s"the host '${req.uri.authority.host}' cannot be resolved")

    override val errorCode: Option[StatusCode] = None
  }

  /**
    * A timeout error.
    */
  final case class HttpTimeoutError(req: HttpRequest, message: String) extends HttpClientError {
    override val reason: String                =
      s"an HTTP response to endpoint '${req.uri}' with method '${req.method}' resulted in a timeout"
    override val details: Option[String]       = Some(s"the request timed out due to '$message'")
    override val errorCode: Option[StatusCode] = None
  }

  final case class InvalidEncoding(req: HttpRequest, encoding: HttpEncoding) extends HttpClientError {
    override val reason: String                =
      s"an HTTP response to endpoint '${req.uri}' with method '${req.method}' could not be decoded from '$encoding'"
    override val details: Option[String]       = Some(s"Encoding '$encoding' not supported")
    override val errorCode: Option[StatusCode] = None
  }

  /**
    * A serialization error when attempting to cast response.
    */
  final case class HttpSerializationError(req: HttpRequest, message: String, tpe: String) extends HttpClientError {
    override val reason: String                =
      s"an HTTP response to endpoint '${req.uri}' with method '${req.method}' could not be converted to type '$tpe'"
    override val details: Option[String]       = Some(s"the serialization failed due to '$message'")
    override val errorCode: Option[StatusCode] = None
  }

  /**
    * A Client status error (HTTP status codes 4xx).
    */
  final case class HttpClientStatusError(
      req: HttpRequest,
      code: StatusCodes.ClientError,
      responseHeaders: Seq[HttpHeader],
      message: String
  ) extends HttpClientError {
    override val reason: String                =
      s"An HTTP response to endpoint '${req.uri}' with method '${req.method}' that should have been successful, returned the HTTP status code '$code'"
    override val details: Option[String]       = Some(s"""The request failed:
         |Headers: ${responseHeaders.mkString(", ")}
         |Body: $message
         |""".stripMargin)
    override val body: Option[String]          = Some(message)
    override val errorCode: Option[StatusCode] = Some(code)

  }

  object HttpClientStatusError {

    def apply(req: HttpRequest, code: StatusCodes.ClientError, message: String): HttpClientStatusError =
      HttpClientStatusError(req, code, Seq.empty, message)
  }

  /**
    * A server status error (HTTP status codes 5xx).
    */
  final case class HttpServerStatusError(req: HttpRequest, code: StatusCodes.ServerError, message: String)
      extends HttpClientError {
    override val reason: String                =
      s"an HTTP response to endpoint '${req.uri}' with method '${req.method}' that should have been successful, returned the HTTP status code '$code'"
    override val details: Option[String]       = Some(s"the request failed with body '$message'")
    override val body: Option[String]          = Some(message)
    override val errorCode: Option[StatusCode] = Some(code)

  }

  /**
    * Some other response error which is not 4xx nor 5xx
    */
  final case class HttpUnexpectedStatusError(req: HttpRequest, code: StatusCode, message: String)
      extends HttpClientError {
    override val reason: String                =
      s"an HTTP response to endpoint '${req.uri}' with method '${req.method}' that should have been successful, returned the HTTP status code '$code'"
    override val details: Option[String]       = Some(s"the request failed with body '$message'")
    override val body: Option[String]          = Some(message)
    override val errorCode: Option[StatusCode] = Some(code)

  }

}
