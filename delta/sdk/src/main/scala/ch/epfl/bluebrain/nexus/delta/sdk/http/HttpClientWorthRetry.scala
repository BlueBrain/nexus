package ch.epfl.bluebrain.nexus.delta.sdk.http

import akka.http.scaladsl.model.StatusCodes.GatewayTimeout
import ch.epfl.bluebrain.nexus.delta.sdk.http.HttpClientError.{HttpServerStatusError, HttpTimeoutError, HttpUnexpectedError}

trait HttpClientWorthRetry extends (HttpClientError => Boolean)

object HttpClientWorthRetry {

  /**
    * It is always worth retrying.
    */
  val always: HttpClientWorthRetry = _ => true

  /**
    * It is never worth retrying.
    */
  val never: HttpClientWorthRetry = _ => false

  /**
    * It is worth retrying if there is a server error (except for GatewayTimeout), a timeout or an unexpected error.
    */
  val onServerError: HttpClientWorthRetry = {
    case HttpServerStatusError(_, code, _) if code != GatewayTimeout             => true
    case _: HttpTimeoutError                                                     => true
    case err: HttpUnexpectedError if !err.message.contains("Connection refused") => true
    case _                                                                       => false
  }

  /**
    * Constructs a [[HttpClientWorthRetry]] by the name of the strategy. Allowed strategies are 'always', 'never' or
    * 'onServerError'
    */
  final def byName(string: String): Option[HttpClientWorthRetry] =
    string match {
      case "always"        => Some(always)
      case "never"         => Some(never)
      case "onServerError" => Some(onServerError)
      case _               => None
    }
}
