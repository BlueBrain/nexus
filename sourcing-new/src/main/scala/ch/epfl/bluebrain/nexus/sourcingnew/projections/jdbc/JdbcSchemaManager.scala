package ch.epfl.bluebrain.nexus.sourcingnew.projections.jdbc

import cats.effect.{IO, LiftIO}
import ch.epfl.bluebrain.nexus.sourcingnew.projections.SchemaManager
import org.flywaydb.core.Flyway

class JdbcSchemaManager[F[_]: LiftIO](jdbcConfig: JdbcConfig)
  extends SchemaManager[F] {

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
