package ch.epfl.bluebrain.nexus.cli.influxdb.config

import java.nio.file.Path

import cats.implicits._
import ch.epfl.bluebrain.nexus.cli.ProjectLabelRef
import ch.epfl.bluebrain.nexus.cli.config.NexusConfig.defaultPath
import ch.epfl.bluebrain.nexus.cli.config.{RetryStrategyConfig, ConfigReader => CliConfigReader}
import ch.epfl.bluebrain.nexus.cli.error.ConfigError
import ch.epfl.bluebrain.nexus.cli.influxdb.config.InfluxDbConfig.{DataConfig, IndexingConfig, InfluxDbClientConfig}
import ch.epfl.bluebrain.nexus.cli.types.Label
import com.typesafe.config.ConfigFactory
import org.http4s.Uri
import pureconfig.error.CannotConvert
import pureconfig.{ConfigConvert, ConfigReader, ConfigWriter}
import pureconfig.generic.semiauto.deriveConvert
import pureconfig.generic.auto._

import scala.concurrent.duration.FiniteDuration

/**
  * InfluxDB indexer configuration.
  * @param data      configuration regarding which data to index
  * @param indexing  configuration related to the indexing process
  * @param client    InfluxDB configuration
  */
case class InfluxDbConfig(data: DataConfig, indexing: IndexingConfig, client: InfluxDbClientConfig)

object InfluxDbConfig {

  implicit val uriConfigReader: ConfigReader[Uri] =
    ConfigReader[String].emap { str =>
      Uri.fromString(str).leftMap(err => CannotConvert(str, classOf[Uri].getCanonicalName, err.details))
    }
  implicit val uriConfigWriter: ConfigWriter[Uri] =
    ConfigWriter[String].contramap(_.renderString)

  final case class TypeConfig(`type`: Uri, query: String, measurement: String, values: Set[String], timestamp: String)
  final case class ProjectConfig(
      sparqlView: Uri,
      database: String,
      types: Seq[TypeConfig]
  ) {
    def findTypes(eventTypes: Set[Uri]): Seq[TypeConfig] = {
      types.filter(typeConf => eventTypes(typeConf.`type`))

    }
  }

  final case class DataConfig(projects: Map[String, ProjectConfig]) {
    def configOf(ref: ProjectLabelRef): Option[ProjectConfig] = ref match {
      case (Label(org), Label(proj)) => projects.get(s"$org/$proj")
    }
  }

  final case class InfluxDbClientConfig(endpoint: Uri, retry: RetryStrategyConfig, duration: String, replication: Int)

  final case class IndexingConfig(
      sparqlConcurrency: Int,
      influxdbConcurrency: Int,
      offsetSaveInterval: FiniteDuration,
      offsetFile: Path
  )

  private lazy val referenceConfig = ConfigFactory.defaultReference()

  /**
    * Attempts to construct an InfluxDB configuration from the default path ~/.nexus/influxdb.conf.
    *
    * If that path does not exists, the default configuration in ''reference.conf'' will be used.
    */
  def apply()(implicit reader: CliConfigReader[InfluxDbConfig]): Either[ConfigError, InfluxDbConfig] =
    defaultPath.flatMap(path => reader(path, "influxdb", referenceConfig))

  /**
    * Attempts to construct an InfluxDB configuration from the passed path. If the path is not provided,
    * the default path ~/.nexus/influxdb.conf will be used.
    *
    * If that path does not exists, the default configuration in ''reference.conf'' will be used.
    */
  def apply(path: Path)(implicit reader: CliConfigReader[InfluxDbConfig]): Either[ConfigError, InfluxDbConfig] =
    reader(path, "influxdb", referenceConfig)

  /**
    * Attempts to construct an InfluxDB configuration from the passed path. If the path is not provided,
    * the default path ~/.nexus/influxdb.conf will be used.
    * If that path does not exists, the default configuration in ''reference.conf'' will be used.
    *
    * The rest of the parameters, if present, will override the resulting InfluxDB configuration parameters.
    */
  def withDefaults(
      path: Option[Path] = None,
      influxDbEndpoint: Option[Uri] = None
  )(implicit reader: CliConfigReader[InfluxDbConfig]): Either[ConfigError, InfluxDbConfig] =
    path.map(apply(_)).getOrElse(apply()).map { config =>
      config.copy(client = config.client.copy(endpoint = mergeOpt(config.client.endpoint, influxDbEndpoint)))
    }

  private def mergeOpt[A](one: A, other: Option[A]): A = other.getOrElse(one)

  implicit val influxDbConfigConvert: ConfigConvert[InfluxDbConfig] = deriveConvert[InfluxDbConfig]

}
