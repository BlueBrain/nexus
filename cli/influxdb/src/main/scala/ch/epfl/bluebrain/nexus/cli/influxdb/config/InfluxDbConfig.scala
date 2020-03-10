package ch.epfl.bluebrain.nexus.cli.influxdb.config

import cats.implicits._
import ch.epfl.bluebrain.nexus.cli.ProjectLabelRef
import ch.epfl.bluebrain.nexus.cli.config.RetryStrategyConfig
import ch.epfl.bluebrain.nexus.cli.influxdb.config.InfluxDbConfig.{DataConfig, IndexingConfig, InfluxDbClientConfig}
import ch.epfl.bluebrain.nexus.cli.types.Label
import org.http4s.Uri
import pureconfig.error.CannotConvert
import pureconfig.{ConfigReader, ConfigWriter}

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
      influxdbConcurrency: Int
  )

}
