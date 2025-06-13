package ai.senscience.nexus.delta.config

import cats.effect.IO
import cats.syntax.all.*
import ch.epfl.bluebrain.nexus.delta.kernel.cache.CacheConfig
import ch.epfl.bluebrain.nexus.delta.kernel.config.Configs
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclsConfig
import ch.epfl.bluebrain.nexus.delta.sdk.fusion.FusionConfig
import ch.epfl.bluebrain.nexus.delta.sdk.jws.JWSConfig
import ch.epfl.bluebrain.nexus.delta.sdk.model.ServiceAccountConfig
import ch.epfl.bluebrain.nexus.delta.sdk.organizations.OrganizationsConfig
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.PermissionsConfig
import ch.epfl.bluebrain.nexus.delta.sdk.projects.ProjectsConfig
import ch.epfl.bluebrain.nexus.delta.sdk.realms.RealmsConfig
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.ResolversConfig
import ch.epfl.bluebrain.nexus.delta.sdk.resources.ResourcesConfig
import ch.epfl.bluebrain.nexus.delta.sdk.schemas.SchemasConfig
import ch.epfl.bluebrain.nexus.delta.sdk.sse.SseConfig
import ch.epfl.bluebrain.nexus.delta.sdk.typehierarchy.TypeHierarchyConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.config.{DatabaseConfig, ElemQueryConfig}
import ch.epfl.bluebrain.nexus.delta.sourcing.exporter.ExportConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.config.{ProjectLastUpdateConfig, ProjectionConfig}
import com.typesafe.config.Config
import pureconfig.ConfigReader
import pureconfig.generic.semiauto.deriveReader

import java.io.{File, InputStreamReader}
import java.nio.charset.StandardCharsets.UTF_8

/**
  * Main application configuration.
  */
final case class AppConfig(
    description: DescriptionConfig,
    http: HttpConfig,
    database: DatabaseConfig,
    identities: CacheConfig,
    permissions: PermissionsConfig,
    realms: RealmsConfig,
    organizations: OrganizationsConfig,
    acls: AclsConfig,
    projects: ProjectsConfig,
    resolvers: ResolversConfig,
    resources: ResourcesConfig,
    schemas: SchemasConfig,
    typeHierarchy: TypeHierarchyConfig,
    serviceAccount: ServiceAccountConfig,
    elemQuery: ElemQueryConfig,
    sse: SseConfig,
    projections: ProjectionConfig,
    projectLastUpdate: ProjectLastUpdateConfig,
    fusion: FusionConfig,
    `export`: ExportConfig,
    jws: JWSConfig
)

object AppConfig {

  /**
    * Loads the application in two steps, wrapping the error type:
    *
    *   1. loads the default default.conf and identifies the database configuration
    *   2. reloads the config using the selected database configuration and the plugin configurations
    */
  def loadOrThrow(
      externalConfigPath: Option[String] = None,
      pluginsConfigPaths: List[String] = List.empty,
      accClassLoader: ClassLoader = getClass.getClassLoader
  ): IO[(AppConfig, Config)] =
    load(externalConfigPath, pluginsConfigPaths, accClassLoader)

  /**
    * Loads the application in two steps:
    *
    *   1. loads the default default.conf and identifies the database configuration
    *   2. reloads the config using the selected database configuration and the plugin configurations
    */
  def load(
      externalConfigPath: Option[String] = None,
      pluginsConfigPaths: List[String] = List.empty,
      accClassLoader: ClassLoader = getClass.getClassLoader
  ): IO[(AppConfig, Config)] = {
    for {
      externalConfig            <- Configs.parseFile(externalConfigPath.map(new File(_)))
      defaultConfig             <- Configs.parseResource("default.conf")
      pluginConfigs             <- pluginsConfigPaths.traverse { path =>
                                     Configs.parseReader(new InputStreamReader(accClassLoader.getResourceAsStream(path), UTF_8))
                                   }
      (appConfig, mergedConfig) <- Configs.merge[AppConfig]("app", externalConfig :: defaultConfig :: pluginConfigs: _*)
    } yield (appConfig, mergedConfig)
  }

  implicit final val appConfigReader: ConfigReader[AppConfig] =
    deriveReader[AppConfig]
}
