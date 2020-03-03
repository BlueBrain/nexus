package ch.epfl.bluebrain.nexus.cli

import ch.epfl.bluebrain.nexus.cli.ClientError.ServerStatusError
import org.http4s.Status

trait ClientRetryCondition {

  /**
    * Decides whether it is worth to retry or not depending on the passed [[ClientError]].
    * The actual retry will depend on the [[ch.epfl.bluebrain.nexus.cli.config.RetryStrategyConfig]].
    *
    * @param error the client error
    * @return true = retry; false = do not retry
    */
  def apply(error: ClientError): Boolean

  /**
    * Decides whether it is worth to retry or not depending on the passed either of [[ClientError]].
    * The actual retry will depend on the [[ch.epfl.bluebrain.nexus.cli.config.RetryStrategyConfig]].
    *
    * @param either an [[Either]] where the Left is a [[ClientError]]
    * @return true = retry; false = do not retry
    */
  def fromEither[A](either: ClientErrOr[A]): Boolean =
    either match {
      case Left(err) => apply(err)
      case _         => false
    }
}

// $COVERAGE-OFF$
object ClientRetryCondition {

  /**
    * Do not retry on any type of client error.
    */
  val never: ClientRetryCondition = _ => false

  /**
    * Retry when the Client response returns a HTTP Server Error (status codes 5xx) that is not a [[GatewayTimeout]].
    */
  val onServerError: ClientRetryCondition = {
    case ServerStatusError(status, _) => status != Status.GatewayTimeout
    case _                            => false
  }

  /**
    * Retry on any client error.
    */
  val always: ClientRetryCondition = _ => true

}
// $COVERAGE-ON$
