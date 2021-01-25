package ch.epfl.bluebrain.nexus.delta.sdk.http

import ch.epfl.bluebrain.nexus.delta.kernel.{RetryStrategy, RetryStrategyConfig}
import com.typesafe.scalalogging.Logger
import pureconfig.ConfigReader
import pureconfig.error.CannotConvert
import ch.epfl.bluebrain.nexus.delta.sdk.http.HttpClientConfig.logger
import pureconfig.generic.semiauto.deriveReader

import scala.annotation.nowarn

/**
  * Http Client configuration.
  *
  * @param retry           the retry configuration
  * @param isWorthRetrying the strategy to decide if it is worth retrying when an Http error occurs.
  *                        Allowed strategies are 'always', 'never' or 'onServerError'.
  */
final case class HttpClientConfig(retry: RetryStrategyConfig, isWorthRetrying: HttpClientWorthRetry) {

  /**
    * @return the retry strategy from the current configuration
    */
  def strategy: RetryStrategy[HttpClientError] =
    RetryStrategy(retry, isWorthRetrying, RetryStrategy.logError(logger, "http client"))
}

object HttpClientConfig {

  private[http] val logger: Logger = Logger[HttpClientConfig]

  @nowarn("cat=unused")
  implicit private val httpClientWorthRetryConverter: ConfigReader[HttpClientWorthRetry] =
    ConfigReader.fromString[HttpClientWorthRetry](string =>
      HttpClientWorthRetry
        .byName(string)
        .toRight(
          CannotConvert(
            string,
            "HttpClientWorthRetry",
            "'isWorthRetrying' value must be one of ('always', 'never', 'onServerError')"
          )
        )
    )

  implicit final val httpClientConfigReader: ConfigReader[HttpClientConfig] =
    deriveReader[HttpClientConfig]

}
