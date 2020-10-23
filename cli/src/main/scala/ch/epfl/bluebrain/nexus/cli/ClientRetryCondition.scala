package ch.epfl.bluebrain.nexus.cli

import ch.epfl.bluebrain.nexus.cli.CliError.ClientError
import ch.epfl.bluebrain.nexus.cli.CliError.ClientError.{ServerStatusError, Unexpected}
import org.http4s.Status
import pureconfig.ConfigConvert
import pureconfig.error.CannotConvert

sealed trait ClientRetryCondition {

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

  /**
    * Decide whether it is worth to avoid retrying or not depending on the passed [[ClientError]].
    * The actual retry will depend on the [[ch.epfl.bluebrain.nexus.cli.config.RetryStrategyConfig]].
    *
    * @param error the client error
    * @return true = do not retry; false = retry
    */
  def notRetry(error: ClientError): Boolean = !apply(error)

  /**
    * Decide whether it is worth to avoid retrying or not depending on the passed either of [[ClientError]].
    * The actual retry will depend on the [[ch.epfl.bluebrain.nexus.cli.config.RetryStrategyConfig]].
    *
    * @param either an [[Either]] where the Left is a [[ClientError]]
    * @return true = do not retry; false = retry
    */
  def notRetryFromEither[A](either: ClientErrOr[A]): Boolean = !fromEither(either)

}

// $COVERAGE-OFF$
object ClientRetryCondition {

  /**
    * Do not retry on any type of client error.
    */
  final case object Never extends ClientRetryCondition {
    override def apply(error: ClientError): Boolean = false
  }

  /**
    * Retry when the Client response returns a HTTP Server Error (status codes 5xx) that is not a [[Status.GatewayTimeout]].
    * Alternatively, retries when the Client returns an Unexpected error
    */
  final case object OnServerError extends ClientRetryCondition {
    override def apply(error: ClientError): Boolean =
      error match {
        case ServerStatusError(status, _) => status != Status.GatewayTimeout
        case _: Unexpected                => true
        case _                            => false
      }
  }

  /**
    * Retry on any client error.
    */
  final case object Always extends ClientRetryCondition {
    override def apply(error: ClientError): Boolean = true
  }

  implicit final val clientRetryConditionConfigConvert: ConfigConvert[ClientRetryCondition] =
    ConfigConvert.viaString(
      {
        case "always"          => Right(Always)
        case "on-server-error" => Right(OnServerError)
        case "never"           => Right(Never)
        case other             =>
          Left(
            CannotConvert(
              other,
              "ClientRetryCondition",
              s"Unknown value 'other', accepted values are: [always, on-server-error, never]"
            )
          )
      },
      {
        case Always        => "always"
        case OnServerError => "on-server-error"
        case Never         => "never"
      }
    )

}
// $COVERAGE-ON$
