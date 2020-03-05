package ch.epfl.bluebrain.nexus.cli.influxdb.config

import cats.implicits._
import ch.epfl.bluebrain.nexus.cli.config.RetryStrategyConfig
import ch.epfl.bluebrain.nexus.cli.influxdb.config.InfluxDbConfig.{DataConfig, IndexingConfig, InfluxDbClientConfig}
import ch.epfl.bluebrain.nexus.cli.influxdb.{ProjectRef, SparqlQueryTemplate}
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

  case class ProjectConfig(
      sparqlView: Uri,
      typePrefix: Uri,
      types: Map[String, String]
  ) {
    def findTemplate(eventTypes: Set[Uri]): Option[(String, SparqlQueryTemplate)] = {
      types.foldLeft[Option[(String, SparqlQueryTemplate)]](None) {
        case (Some(value), _)                                              => Some(value)
        case (_, (tpe, template)) if eventTypes.contains(typePrefix / tpe) => Some((tpe, SparqlQueryTemplate(template)))
        case _                                                             => None
      }
    }
  }

  case class DataConfig(projects: Map[String, ProjectConfig]) {
    def configOf(ref: ProjectRef): Option[ProjectConfig] =
      projects.get(ref.asString)
  }

  case class InfluxDbClientConfig(endpoint: Uri, retry: RetryStrategyConfig, duration: String, replication: Int)

  case class IndexingConfig(
      sparqlConcurrency: Int,
      influxdbConcurrency: Int
  )

}
