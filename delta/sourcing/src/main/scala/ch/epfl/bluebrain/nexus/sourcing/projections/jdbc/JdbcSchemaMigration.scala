package ch.epfl.bluebrain.nexus.sourcing.projections.jdbc

import ch.epfl.bluebrain.nexus.sourcing.projections.SchemaMigration
import monix.bio.Task
import org.flywaydb.core.Flyway

/**
  * Apply the migration scripts on a SQL compliant database
  *
  * Relies on https://flywaydb.org/
  *
  * @param jdbcConfig the config to connect to the database
  */
private[projections] class JdbcSchemaMigration(jdbcConfig: JdbcConfig) extends SchemaMigration {

  def migrate(): Task[Unit] = {
    for {
      flyway <- Task {
                  Flyway
                    .configure()
                    .dataSource(jdbcConfig.url, jdbcConfig.username, jdbcConfig.password)
                    .locations("classpath:scripts/postgres")
                    .load()
                }
      _      <- Task(flyway.migrate())
    } yield ()
  }
}
