package ch.epfl.bluebrain.nexus.delta.config

import ch.epfl.bluebrain.nexus.sourcing.config.{CassandraConfig, PostgresConfig}

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
