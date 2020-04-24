package ch.epfl.bluebrain.nexus.cli.config

/**
  * Complete application configuration.
  * @param env the environment configuration
  */
final case class AppConfig(
    env: EnvConfig
)

object AppConfig {}
