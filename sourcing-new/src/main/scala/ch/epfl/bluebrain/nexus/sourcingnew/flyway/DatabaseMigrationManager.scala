package ch.epfl.bluebrain.nexus.sourcingnew.flyway

import cats.effect.{IO, LiftIO}
import ch.epfl.bluebrain.nexus.sourcingnew.projections.JdbcConfig
import org.flywaydb.core.Flyway

class DatabaseMigrationManager[F[_]: LiftIO](jdbcConfig: JdbcConfig) {

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
      _ <- IO(flyway.validate())
      _ <- IO(flyway.migrate())
    } yield ()
    migration.to[F]
  }

}
