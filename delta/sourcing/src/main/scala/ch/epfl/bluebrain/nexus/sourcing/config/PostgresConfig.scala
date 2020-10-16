package ch.epfl.bluebrain.nexus.sourcing.config

import doobie.util.transactor.Transactor
import doobie.util.transactor.Transactor.Aux
import monix.bio.Task

/**
  * Configuration when using PostgreSQL to persist data
  */
final case class PostgresConfig(
    host: String,
    port: Int,
    database: String,
    username: String,
    password: String,
    url: String
) {

  /**
    * A doobie transactor
    */
  def transactor: Aux[Task, Unit] =
    Transactor.fromDriverManager[Task](
      "org.postgresql.Driver",
      url,
      username,
      password
    )
}
