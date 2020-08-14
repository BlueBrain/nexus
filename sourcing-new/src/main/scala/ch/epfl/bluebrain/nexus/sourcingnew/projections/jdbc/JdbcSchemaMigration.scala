package ch.epfl.bluebrain.nexus.sourcingnew.projections.jdbc

import cats.effect.{IO, LiftIO}
import ch.epfl.bluebrain.nexus.sourcingnew.projections.SchemaMigration
import org.flywaydb.core.Flyway

/**
  * Apply the migration scripts on a SQL compliant database
  *
  * Relies on https://flywaydb.org/
  *
  * @param jdbcConfig
  * @tparam F
  */
class JdbcSchemaMigration[F[_]: LiftIO](jdbcConfig: JdbcConfig)
  extends SchemaMigration[F] {

  def migrate(): F[Unit] = {
    val migration = for {
      flyway <- IO {
        Flyway.configure()
          .dataSource(
            jdbcConfig.url,
            jdbcConfig.username,
            jdbcConfig.password)
          .locations("classpath:scripts/postgres")
          .load()
      }
      _ <- IO(flyway.migrate())
    } yield ()
    migration.to[F]
  }

}
