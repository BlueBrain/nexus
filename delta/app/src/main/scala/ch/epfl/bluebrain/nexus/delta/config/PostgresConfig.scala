package ch.epfl.bluebrain.nexus.delta.config

/**
  * The postgres database config.
  * @param host     the database host
  * @param port     the database port
  * @param database the database name
  * @param username the database username
  * @param password the database password
  */
final case class PostgresConfig(
    host: String,
    port: Int,
    database: String,
    username: String,
    password: String
)
