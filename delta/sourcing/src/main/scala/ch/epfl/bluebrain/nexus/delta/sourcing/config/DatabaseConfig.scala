package ch.epfl.bluebrain.nexus.delta.sourcing.config

import pureconfig.ConfigReader
import pureconfig.generic.semiauto.deriveReader

import scala.annotation.nowarn

/**
  * The database config.
  *
  * @param flavour            the database flavour
  * @param postgres           the postgres configuration
  * @param cassandra          the cassandra configuration
  * @param verifyIdUniqueness When set to true checks that the ids are unique inside a project and across module types (resources, schemas, resolvers, etc).
  *                           This is desired but it has a performance penalty on resources creation.
  *                           If you know id duplication will not happen in your deployment, you can set this flag to false.
  */
final case class DatabaseConfig(
    flavour: DatabaseFlavour,
    postgres: PostgresConfig,
    cassandra: CassandraConfig,
    verifyIdUniqueness: Boolean
)

object DatabaseConfig {

  @nowarn("cat=unused")
  implicit final val databaseConfigReader: ConfigReader[DatabaseConfig] = {
    implicit val postgresConfigReader: ConfigReader[PostgresConfig]   = deriveReader[PostgresConfig]
    implicit val cassandraConfigReader: ConfigReader[CassandraConfig] = deriveReader[CassandraConfig]
    deriveReader[DatabaseConfig]
  }
}
