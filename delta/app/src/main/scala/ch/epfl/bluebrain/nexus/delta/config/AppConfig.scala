package ch.epfl.bluebrain.nexus.delta.config

import com.typesafe.config.{Config, ConfigFactory}
import monix.bio.{IO, UIO}
import pureconfig.ConfigSource
import pureconfig.error.ConfigReaderFailures
import pureconfig.generic.auto._

/**
  * Main application configuration.
  * @param description the service description
  * @param http        the http config
  * @param cluster     the cluster config
  * @param database    the database config
  * @param permissions the permissions config
  */
final case class AppConfig(
    description: DescriptionConfig,
    http: HttpConfig,
    cluster: ClusterConfig,
    database: DatabaseConfig,
    permissions: PermissionsConfig
)

object AppConfig {

  /**
    * Loads the application in two steps:<br/>
    * 1. loads the default default.conf and identifies the database configuration<br/>
    * 2. reloads the config using the selected database configuration
    */
  def load(): IO[ConfigReaderFailures, (AppConfig, Config)] = {
    for {
      defaultConfig <- UIO.delay(ConfigFactory.load("default.conf"))
      default       <- UIO.delay(ConfigSource.fromConfig(defaultConfig).at("app").load[AppConfig])
      flavour       <- IO.fromEither(default.map(_.database.flavour))
      file           = flavour match {
                         case DatabaseFlavour.Postgres  => "application-postgresql.conf"
                         case DatabaseFlavour.Cassandra => "application-cassandra.conf"
                       }
      config        <- UIO.delay(ConfigFactory.load(file))
      loaded        <- UIO.delay(ConfigSource.fromConfig(config).at("app").load[AppConfig])
      appConfig     <- IO.fromEither(loaded)
    } yield (appConfig, config)
  }
}
