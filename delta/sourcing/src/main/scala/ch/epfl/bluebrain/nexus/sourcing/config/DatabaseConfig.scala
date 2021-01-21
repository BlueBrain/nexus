package ch.epfl.bluebrain.nexus.sourcing.config

import pureconfig.ConfigReader
import pureconfig.generic.semiauto.deriveReader

import scala.annotation.nowarn

/**
  * The database config.
  *
  * @param flavour   the database flavour
  * @param postgres  the postgres configuration
  * @param cassandra the cassandra configuration
  */
final case class DatabaseConfig(
    flavour: DatabaseFlavour,
    postgres: PostgresConfig,
    cassandra: CassandraConfig
)

object DatabaseConfig {

  @nowarn("cat=unused")
  implicit final val databaseConfigReader: ConfigReader[DatabaseConfig] = {
    implicit val postgresConfigReader: ConfigReader[PostgresConfig]   = deriveReader[PostgresConfig]
    implicit val cassandraConfigReader: ConfigReader[CassandraConfig] = deriveReader[CassandraConfig]
    deriveReader[DatabaseConfig]
  }
}
