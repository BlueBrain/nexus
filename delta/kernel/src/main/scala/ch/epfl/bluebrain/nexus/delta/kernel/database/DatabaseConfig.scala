package ch.epfl.bluebrain.nexus.delta.kernel.database

import ch.epfl.bluebrain.nexus.delta.kernel.Secret
import ch.epfl.bluebrain.nexus.delta.kernel.database.DatabaseConfig.DatabaseAccess
import pureconfig.ConfigReader
import pureconfig.generic.semiauto.deriveReader

import scala.annotation.nowarn

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
  */
final case class DatabaseConfig(
    read: DatabaseAccess,
    write: DatabaseAccess,
    streaming: DatabaseAccess,
    name: String,
    username: String,
    password: Secret[String],
    tablesAutocreate: Boolean
) {}

object DatabaseConfig {

  @nowarn("cat=unused")
  implicit final val databaseConfigReader: ConfigReader[DatabaseConfig] = {
    implicit val accessReader: ConfigReader[DatabaseAccess] = deriveReader[DatabaseAccess]
    deriveReader[DatabaseConfig]
  }

  final case class DatabaseAccess(host: String, port: Int, poolSize: Int)

}
