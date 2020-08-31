package ch.epfl.bluebrain.nexus.sourcingnew.projections.jdbc

import doobie.util.transactor.Transactor
import doobie.util.transactor.Transactor.Aux
import monix.bio.Task

/**
 * Configuration when using PostgreSQL to persist data
 */
final case class JdbcConfig(
    host: String,
    port: Int,
    database: String,
    username: String,
    password: String,
    driver: String = "org.postgresql.Driver"
) {

  /**
   * Connection url
   */
  def url: String = s"jdbc:postgresql://$host:$port/$database?stringtype=unspecified"

  /**
   * A doobie transactor
   */
  def transactor: Aux[Task, Unit] =
    Transactor.fromDriverManager[Task](
      driver,
      url,
      username,
      password
    )
}
