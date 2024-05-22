package ch.epfl.bluebrain.nexus.delta.sourcing.config

import ch.epfl.bluebrain.nexus.delta.kernel.Secret
import ch.epfl.bluebrain.nexus.delta.kernel.cache.CacheConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.config.DatabaseConfig.DatabaseAccess
import pureconfig.ConfigReader
import pureconfig.generic.semiauto.deriveReader

import scala.concurrent.duration.FiniteDuration

/**
  * Database configuration
  * @param read
  *   Access to database for regular read access (fetch and listing operations)
  * @param write
  *   Access to database for write access
  * @param streaming
  *   Access to database for streaming access (indexing / SSEs)
  * @param name
  *   The name of the database to connect to
  * @param username
  *   The database username
  * @param password
  *   The database password
  * @param tablesAutocreate
  *   When true it creates the tables on service boot
  * @param slowQueryThreshold
  *   Threshold allowing to trigger a warning log when a query execution time reaches this limit
  * @param cache
  *   The cache configuration for the partitions cache
  */
final case class DatabaseConfig(
    read: DatabaseAccess,
    write: DatabaseAccess,
    streaming: DatabaseAccess,
    name: String,
    username: String,
    password: Secret[String],
    tablesAutocreate: Boolean,
    slowQueryThreshold: FiniteDuration,
    cache: CacheConfig
)

object DatabaseConfig {

  implicit final val databaseConfigReader: ConfigReader[DatabaseConfig] = {
    implicit val accessReader: ConfigReader[DatabaseAccess] = deriveReader[DatabaseAccess]
    deriveReader[DatabaseConfig]
  }

  final case class DatabaseAccess(host: String, port: Int, poolSize: Int)

}
