package ch.epfl.bluebrain.nexus.sourcingnew.projections.jdbc

import doobie.util.transactor.Transactor
import doobie.util.transactor.Transactor.Aux
import monix.bio.Task

final case class JdbcConfig(
    host: String,
    port: Int,
    database: String,
    username: String,
    password: String,
    driver: String = "org.postgresql.Driver"
) {
  def url: String = s"jdbc:postgresql://$host:$port/$database?stringtype=unspecified"

  def transactor: Aux[Task, Unit] =
    Transactor.fromDriverManager[Task](
      driver,
      url,
      username,
      password
    )
}
