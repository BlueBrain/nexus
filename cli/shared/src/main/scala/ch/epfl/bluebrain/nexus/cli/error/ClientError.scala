package ch.epfl.bluebrain.nexus.cli.error

import cats.Show
import cats.effect.Sync
import cats.implicits._
import ch.epfl.bluebrain.nexus.cli.ClientErrOr
import org.http4s.{Response, Status}

import scala.util.Try

/**
  * Enumeration of possible Client errors.
  */
@SuppressWarnings(Array("IncorrectlyNamedExceptions"))
sealed trait ClientError extends CliError

@SuppressWarnings(Array("IncorrectlyNamedExceptions"))
object ClientError {

  /**
    * Attempt to construct a [[ClientError]] from the passed HTTP status code and message
    *
    * @return Some(clientError) if the code is not Successful, false otherwise
    */
  def apply(code: Status, message: String): Option[ClientError] =
    Try(unsafe(code, message)).toOption

  /**
    * Construct a [[ClientError]] from the passed HTTP status code and message
    *
    * @throws IllegalArgumentException if the code is a Successful HTTP Status code is 2xx
    */
  def unsafe(code: Status, message: String): ClientError =
    code.responseClass match {
      case Status.Successful =>
        throw new IllegalArgumentException(s"Successful code '$code cannot be converted to a ClientError'")
      case Status.ClientError =>
        ClientStatusError(code, message)
      case Status.ServerError => ServerStatusError(code, message)
      case _ =>
        Unexpected(code, message)
    }

  /**
    * A serialization error when attempting to cast response.
    *
    * @param message  the error message
    * @param tpe      the type into which the serialization was attempted
    * @param original the optionally available original payload
    */
  final case class SerializationError(message: String, tpe: String, original: Option[String] = None)
      extends ClientError {
    val reason: String = s"an HTTP response could not be converted to type '$tpe'"
    val lines: List[String] = List(s"the serialization failed due to '$message'") ++
      original.map(orig => s"The message attempted to serialized was: '$orig'.").toList
  }

  /**
    * A Client status error (HTTP status codes 4xx).
    *
    * @param code    the HTTP status code
    * @param message the error message
    */
  final case class ClientStatusError(code: Status, message: String) extends ClientError {
    val reason: String      = s"an HTTP response that should have been successful, returned the HTTP status code '$code'"
    val lines: List[String] = List(s"The request failed due to '$message'")
  }

  /**
    * A server status error (HTTP status codes 5xx).
    *
    * @param code    the HTTP status code
    * @param message the error message
    */
  final case class ServerStatusError(code: Status, message: String) extends ClientError {
    val reason: String      = s"an HTTP response that should have been successful, returned the HTTP status code '$code'"
    val lines: List[String] = List(s"The request failed due to '$message'")
  }

  /**
    * Some other response error which is not 4xx nor 5xx
    *
    * @param code    the HTTP status code
    * @param message the error message
    */
  final case class Unexpected(code: Status, message: String) extends ClientError {
    val reason: String      = s"an HTTP response that should have been successful, returned the HTTP status code '$code'"
    val lines: List[String] = List(s"The request failed due to '$message'")
  }

  def errorOr[F[_]: Sync, A](successF: Response[F] => F[ClientErrOr[A]]): Response[F] => F[ClientErrOr[A]] = {
    case Status.Successful(r)  => successF(r)
    case Status.ClientError(r) => r.bodyAsText.compile.string.map(s => Left(ClientStatusError(r.status, s)))
    case Status.ServerError(r) => r.bodyAsText.compile.string.map(s => Left(ServerStatusError(r.status, s)))
    case r                     => r.bodyAsText.compile.string.map(s => Left(Unexpected(r.status, s)))
  }

  implicit val clientErrorShow: Show[ClientError] = r => (r: CliError).show

}
