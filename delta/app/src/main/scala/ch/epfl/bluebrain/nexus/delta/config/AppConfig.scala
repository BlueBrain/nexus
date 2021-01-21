package ch.epfl.bluebrain.nexus.delta.config

import ch.epfl.bluebrain.nexus.delta.service.acls.AclsConfig
import ch.epfl.bluebrain.nexus.delta.service.organizations.OrganizationsConfig
import ch.epfl.bluebrain.nexus.delta.service.projects.ProjectsConfig
import ch.epfl.bluebrain.nexus.delta.service.realms.RealmsConfig
import ch.epfl.bluebrain.nexus.delta.service.resolvers.ResolversConfig
import ch.epfl.bluebrain.nexus.sourcing.config.{DatabaseConfig, DatabaseFlavour}
import com.typesafe.config.{Config, ConfigFactory, ConfigParseOptions, ConfigResolveOptions}
import monix.bio.{IO, UIO}
import pureconfig.error.ConfigReaderFailures
import pureconfig.generic.semiauto.deriveReader
import pureconfig.{ConfigReader, ConfigSource}

import java.io.InputStreamReader
import java.nio.charset.StandardCharsets.UTF_8

/**
  * Main application configuration.
  *
  * @param description    the service description
  * @param http           the http config
  * @param cluster        the cluster config
  * @param database       the database config
  * @param identities     the identities config
  * @param permissions    the permissions config
  * @param realms         the realms config
  * @param organizations  the organizations config
  * @param acls           the ACLs config
  * @param projects       the projects config
  * @param resolvers      the resolvers config
  * @param resources      the resources config
  * @param schemas        the schemas config
  * @param serviceAccount the service account config
  */
final case class AppConfig(
    description: DescriptionConfig,
    http: HttpConfig,
    cluster: ClusterConfig,
    database: DatabaseConfig,
    identities: IdentitiesConfig,
    permissions: PermissionsConfig,
    realms: RealmsConfig,
    organizations: OrganizationsConfig,
    acls: AclsConfig,
    projects: ProjectsConfig,
    resolvers: ResolversConfig,
    resources: ResourcesConfig,
    schemas: SchemasConfig,
    serviceAccount: ServiceAccountConfig
)

object AppConfig {

  private val parseOptions    = ConfigParseOptions.defaults().setAllowMissing(false)
  private val resolverOptions = ConfigResolveOptions.defaults()

  /**
    * Loads the application in two steps:<br/>
    * 1. loads the default default.conf and identifies the database configuration<br/>
    * 2. reloads the config using the selected database configuration and the plugin configurations
    */
  def load(
      pluginsConfigs: List[String] = List.empty,
      accClassLoader: ClassLoader = getClass.getClassLoader
  ): IO[ConfigReaderFailures, (AppConfig, Config)] =
    loadWithPlugins(pluginsConfigs.map { string =>
      ConfigFactory.parseReader(new InputStreamReader(accClassLoader.getResourceAsStream(string), UTF_8), parseOptions)
    })

  private def loadWithPlugins(pluginConfigs: List[Config]): IO[ConfigReaderFailures, (AppConfig, Config)] = {
    for {
      defaultConfig <- UIO.delay(ConfigFactory.load("default.conf", parseOptions, resolverOptions))
      default       <- UIO.delay(ConfigSource.fromConfig(defaultConfig).at("app").load[AppConfig])
      flavour       <- IO.fromEither(default.map(_.database.flavour))
      file           = flavour match {
                         case DatabaseFlavour.Postgres  => "application-postgresql.conf"
                         case DatabaseFlavour.Cassandra => "application-cassandra.conf"
                       }
      config        <- UIO.delay(ConfigFactory.load(file))
      mergedConfig   = pluginConfigs.foldLeft(config)(_ withFallback _).resolve()
      loaded        <- UIO.delay(ConfigSource.fromConfig(mergedConfig).at("app").load[AppConfig])
      appConfig     <- IO.fromEither(loaded)
    } yield (appConfig, mergedConfig)
  }

  implicit final val appConfigReader: ConfigReader[AppConfig] =
    deriveReader[AppConfig]
}
