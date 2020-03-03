package ch.epfl.bluebrain.nexus.cli

import cats.effect.Sync
import cats.syntax.functor._
import org.http4s.{Response, Status}

/**
  * Enumeration of possible Client errors.
  */
sealed trait ClientError extends Product with Serializable {
  def message: String
}

object ClientError {

  /**
    * A serialization error when attempting to cast response.
    *
    * @param message  the error message
    * @param original the optionally available original payload
    */
  final case class SerializationError(message: String, original: Option[String] = None) extends ClientError

  /**
    * A Client status error (HTTP status codes 4xx).
    *
    * @param code    the HTTP status code
    * @param message the error message
    */
  final case class ClientStatusError(code: Status, message: String) extends ClientError

  /**
    * A server status error (HTTP status codes 5xx).
    *
    * @param code    the HTTP status code
    * @param message the error message
    */
  final case class ServerStatusError(code: Status, message: String) extends ClientError

  /**
    * Some other response error which is not 4xx nor 5xx
    *
    * @param code    the HTTP status code
    * @param message the error message
    */
  final case class Unexpected(code: Status, message: String) extends ClientError

  def errorOr[F[_], A](
      successF: Response[F] => F[Either[ClientError, A]]
  )(implicit F: Sync[F]): Response[F] => F[Either[ClientError, A]] = {
    case Status.Successful(r)  => successF(r)
    case Status.ClientError(r) => r.bodyAsText.compile.string.map(s => Left(ClientStatusError(r.status, s)))
    case Status.ServerError(r) => r.bodyAsText.compile.string.map(s => Left(ServerStatusError(r.status, s)))
    case r                     => r.bodyAsText.compile.string.map(s => Left(Unexpected(r.status, s)))
  }

}
