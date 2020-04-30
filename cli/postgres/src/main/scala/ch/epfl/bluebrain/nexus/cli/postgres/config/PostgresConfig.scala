package ch.epfl.bluebrain.nexus.cli.postgres.config

import ch.epfl.bluebrain.nexus.cli.types.Label
import com.github.ghik.silencer.silent
import pureconfig.configurable._
import pureconfig.error.CannotConvert
import pureconfig.generic.semiauto.deriveConvert
import pureconfig.{ConfigConvert, ConfigReader, ConfigWriter}

/**
  * PostgreSQL connectivity information along with the projection configuration.
  * @param host     the postgres host
  * @param port     the postgres port
  * @param database the database to be used
  * @param username the auth username
  * @param password the auth password
  * @param projects the project to config mapping
  */
final case class PostgresConfig(
    host: String,
    port: Int,
    database: String,
    username: String,
    password: String,
    projects: Map[(Label, Label), ProjectConfig]
) {

  /**
    * The jdbc connection string.
    */
  def jdbcUrl: String =
    s"jdbc:postgresql://$host:$port/$database"

}

object PostgresConfig {

  @silent
  implicit final val postgresConfigConvert: ConfigConvert[PostgresConfig] = {
    implicit val labelTupleMapReader: ConfigReader[Map[(Label, Label), ProjectConfig]] =
      genericMapReader[(Label, Label), ProjectConfig] { key =>
        key.split('/') match {
          case Array(org, proj) if org.trim.nonEmpty && proj.trim.nonEmpty => Right(Label(org.trim) -> Label(proj.trim))
          case _                                                           => Left(CannotConvert(key, "project reference", "the format does not match {org}/{project}"))
        }
      }
    implicit val labelTupleMapWriter: ConfigWriter[Map[(Label, Label), ProjectConfig]] =
      genericMapWriter { case (Label(org), Label(proj)) => s"$org/$proj" }
    deriveConvert[PostgresConfig]
  }

}
