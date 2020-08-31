package ch.epfl.bluebrain.nexus.sourcing.projections

import ch.epfl.bluebrain.nexus.sourcing.projections.jdbc.JdbcConfig
import ch.epfl.bluebrain.nexus.testkit.postgres.PostgresDocker.PostgresSpec
import doobie.implicits._
import org.scalatest.matchers.should.Matchers.contain

class JdbcProjectionSpec extends PostgresSpec with ProjectionSpec {

  import monix.execution.Scheduler.Implicits.global

  private val jdbcConfig =
    JdbcConfig(postgresHostConfig.host, postgresHostConfig.port, "postgres", PostgresUser, PostgresPassword)

  override val projections: Projection[SomeEvent] =
    Projection.jdbc[SomeEvent](jdbcConfig).runSyncUnsafe()

  override val schemaMigration: SchemaMigration =
    SchemaMigration.jdbc(jdbcConfig).runSyncUnsafe()

  "Schema" should {
    "be properly initialized" in {
      val task = for {
        _      <- schemaMigration.migrate()
        tables <- sql"SELECT table_name FROM information_schema.tables where table_schema='public' ORDER BY table_name"
                    .query[String]
                    .to[List]
                    .transact(jdbcConfig.transactor)
      } yield tables

      val expectedTables = List(
        "flyway_schema_history",
        "journal",
        "projections_failures",
        "projections_progress",
        "snapshot"
      )

      task.runSyncUnsafe() should contain theSameElementsInOrderAs expectedTables
    }
  }
}
