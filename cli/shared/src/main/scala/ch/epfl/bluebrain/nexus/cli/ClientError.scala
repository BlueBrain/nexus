package ch.epfl.bluebrain.nexus.cli

import cats.effect.Sync
import cats.syntax.functor._
import org.http4s.{Response, Status}

import scala.util.Try

/**
  * Enumeration of possible Client errors.
  */
sealed abstract class ClientError(message: String) extends Product with Serializable {
  override def toString: String = message
}

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
    * @param original the optionally available original payload
    */
  final case class SerializationError(message: String, original: Option[String] = None)
      extends ClientError(s"Serialization error due to '$message'. Original payload: '$original'")

  /**
    * A Client status error (HTTP status codes 4xx).
    *
    * @param code    the HTTP status code
    * @param message the error message
    */
  final case class ClientStatusError(code: Status, message: String)
      extends ClientError(s"Server responded with client error code '$code'. Reason: '$message'")

  /**
    * A server status error (HTTP status codes 5xx).
    *
    * @param code    the HTTP status code
    * @param message the error message
    */
  final case class ServerStatusError(code: Status, message: String)
      extends ClientError(s"Server responded with server error code '$code'. Reason: '$message'")

  /**
    * Some other response error which is not 4xx nor 5xx
    *
    * @param code    the HTTP status code
    * @param message the error message
    */
  final case class Unexpected(code: Status, message: String)
      extends ClientError(s"Server responded with unexpected error code '$code'. Reason: '$message'")

  def errorOr[F[_]: Sync, A](successF: Response[F] => F[ClientErrOr[A]]): Response[F] => F[ClientErrOr[A]] = {
    case Status.Successful(r)  => successF(r)
    case Status.ClientError(r) => r.bodyAsText.compile.string.map(s => Left(ClientStatusError(r.status, s)))
    case Status.ServerError(r) => r.bodyAsText.compile.string.map(s => Left(ServerStatusError(r.status, s)))
    case r                     => r.bodyAsText.compile.string.map(s => Left(Unexpected(r.status, s)))
  }

}
