package ch.epfl.bluebrain.nexus.sourcingnew.projections

import cats.effect.IO
import ch.epfl.bluebrain.nexus.sourcingnew.{Persistence, ProjectionModule}
import ch.epfl.bluebrain.nexus.sourcingnew.projections.jdbc.JdbcConfig
import ch.epfl.bluebrain.nexus.testkit.postgres.PostgresDocker
import ch.epfl.bluebrain.nexus.testkit.postgres.PostgresDocker.PostgresHostConfig
import distage.ModuleDef
import doobie.implicits._
import doobie.util.transactor.Transactor
import izumi.distage.effect.modules.CatsDIEffectModule
import izumi.distage.model.definition.Activation
import izumi.distage.plugins.PluginConfig
import izumi.distage.testkit.TestConfig
import izumi.distage.testkit.TestConfig.ParallelLevel
import org.scalatest.matchers.should.Matchers.contain

class JdbcProjectionSpec extends ProjectionSpec {

  override protected def config: TestConfig =
    TestConfig(
      pluginConfig = PluginConfig.empty,
      activation = Activation(Persistence -> Persistence.Postgres),
      parallelTests = ParallelLevel.Sequential,
      moduleOverrides = new ModuleDef {
        include(CatsDIEffectModule)
        include(new PostgresDocker.Module[IO])
        include(new ProjectionModule[IO, SomeEvent])
        make[JdbcConfig].fromEffect { host: PostgresHostConfig =>
          IO.pure(
            JdbcConfig(
              host =host.host,
              port = host.port,
              database = "postgres",
              username = "postgres",
              password = "postgres"
            )
          )
        }
      },
      configBaseName = "jdbc-projections-test"
    )

  "Schema" should {
    "be properly initialized" in {
      (schemaManager: SchemaMigration[IO], xa: Transactor[IO]) =>
        val expectedTables = List(
          "flyway_schema_history",
          "journal",
          "projections_failures",
          "projections_progress",
          "snapshot"
        )
        for {
          _     <- schemaManager.migrate()
         tables <- sql"SELECT table_name FROM information_schema.tables where table_schema='public' ORDER BY table_name"
                      .query[String].to[List].transact(xa)
         _  = tables should contain theSameElementsInOrderAs expectedTables
        } yield ()
    }
  }



}
