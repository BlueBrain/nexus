package ch.epfl.bluebrain.nexus.cli.config

/**
  * The HTTP Client configuration
  *
  * @param retry the retry strategy (policy and condition)
  */
final case class ClientConfig(retry: RetryStrategyConfig)
