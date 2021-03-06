package ch.epfl.bluebrain.nexus.delta.sourcing.config

import ch.epfl.bluebrain.nexus.delta.kernel.Secret
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
    password: Secret[String],
    url: String,
    tablesAutocreate: Boolean
) {

  /**
    * A doobie transactor
    */
  def transactor: Aux[Task, Unit] =
    Transactor.fromDriverManager[Task](
      "org.postgresql.Driver",
      url,
      username,
      password.value
    )
}
