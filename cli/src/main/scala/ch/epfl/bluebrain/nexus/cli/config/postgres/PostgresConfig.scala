package ch.epfl.bluebrain.nexus.cli.config.postgres

import java.nio.file.Path

import ch.epfl.bluebrain.nexus.cli.config.{PrintConfig, RetryStrategyConfig}
import ch.epfl.bluebrain.nexus.cli.sse.{OrgLabel, ProjectLabel}
import pureconfig.configurable._
import pureconfig.error.CannotConvert
import pureconfig.generic.semiauto.deriveConvert
import pureconfig.{ConfigConvert, ConfigReader, ConfigWriter}

import scala.annotation.nowarn
import scala.concurrent.duration.FiniteDuration

/**
  * PostgreSQL connectivity information along with the projection configuration.
  *
  * @param host               the postgres host
  * @param port               the postgres port
  * @param database           the database to be used
  * @param username           the auth username
  * @param password           the auth password
  * @param offsetFile         the location where the postgres projection offset should be read / stored
  * @param offsetSaveInterval how frequent to save the stream offset into the offset file
  * @param retry              the retry strategy (policy and condition)
  * @param print              the configuration for printing output to the client
  * @param projects           the project to config mapping
  */
final case class PostgresConfig(
    host: String,
    port: Int,
    database: String,
    username: String,
    password: String,
    offsetFile: Path,
    offsetSaveInterval: FiniteDuration,
    retry: RetryStrategyConfig[Unit],
    print: PrintConfig,
    projects: Map[(OrgLabel, ProjectLabel), ProjectConfig]
) {

  /**
    * The jdbc connection string.
    */
  def jdbcUrl: String =
    s"jdbc:postgresql://$host:$port/$database?stringtype=unspecified"

}

object PostgresConfig {

  @nowarn("cat=unused")
  implicit final val postgresConfigConvert: ConfigConvert[PostgresConfig] = {
    implicit val labelTupleMapReader: ConfigReader[Map[(OrgLabel, ProjectLabel), ProjectConfig]] =
      genericMapReader[(OrgLabel, ProjectLabel), ProjectConfig] { key =>
        key.split('/') match {
          case Array(org, proj) if !org.isBlank && !proj.isBlank => Right(OrgLabel(org.trim) -> ProjectLabel(proj.trim))
          case _                                                 => Left(CannotConvert(key, "project reference", "the format does not match {org}/{project}"))
        }
      }
    implicit val labelTupleMapWriter: ConfigWriter[Map[(OrgLabel, ProjectLabel), ProjectConfig]] =
      genericMapWriter { case (OrgLabel(org), ProjectLabel(proj)) => s"$org/$proj" }
    deriveConvert[PostgresConfig]
  }

}
