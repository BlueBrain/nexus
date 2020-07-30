package ch.epfl.bluebrain.nexus.sourcingnew.projections

import akka.persistence.query.Offset
import cats.effect.{ContextShift, IO}
import ch.epfl.bluebrain.nexus.sourcingnew.projections.ProjectionProgress.{NoProgress, OffsetProgress}
import ch.epfl.bluebrain.nexus.sourcingnew.projections.jdbc.JdbcConfig
import ch.epfl.bluebrain.nexus.testkit.postgres.PostgresDocker
import ch.epfl.bluebrain.nexus.testkit.postgres.PostgresDocker.PostgresHostConfig
import ch.epfl.bluebrain.nexus.testkit.{ShouldMatchers, TestHelpers}
import distage.ModuleDef
import doobie.util.transactor.Transactor
import izumi.distage.effect.modules.CatsDIEffectModule
import izumi.distage.model.definition.Activation
import izumi.distage.plugins.PluginConfig
import izumi.distage.testkit.TestConfig
import izumi.distage.testkit.TestConfig.ParallelLevel
import izumi.distage.testkit.scalatest.DistageSpecScalatest
import org.scalatest.matchers.should.Matchers.{contain, empty}
import doobie.implicits._

import scala.concurrent.ExecutionContext

class JdbcProjectionSpec extends DistageSpecScalatest[IO] with TestHelpers with ShouldMatchers {

  implicit protected val cs: ContextShift[IO] = IO.contextShift(ExecutionContext.global)

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
      configBaseName = "projections-test"
    )

  "Schema" should {
    "be properly initialized" in {
      (schemaManager: SchemaManager[IO], xa: Transactor[IO]) =>
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

  "A Projection" should {
    val id = genString()
    val persistenceId = s"/some/${genString()}"
    val progress = OffsetProgress(Offset.sequence(42), 42, 42, 0)
    val progressUpdated = OffsetProgress(Offset.sequence(888), 888, 888, 0)

    "store and retrieve progress" in {
      (projections: Projection[IO, SomeEvent],schemaManager: SchemaManager[IO]) =>
        for {
          _     <- schemaManager.migrate()
          _ <- projections.recordProgress(id, progress)
          read <- projections.progress(id)
          _ = read shouldEqual progress
          _ <- projections.recordProgress(id, progressUpdated)
          readUpdated <- projections.progress(id)
          _ = readUpdated shouldEqual progressUpdated
        } yield ()
    }


    "retrieve NoProgress for unknown projections" in {
      (projections: Projection[IO, SomeEvent],schemaManager: SchemaManager[IO]) =>
        for {
          _     <- schemaManager.migrate()
          read <- projections.progress(genString())
          _     = read shouldEqual NoProgress
        } yield ()
    }

    val firstOffset: Offset  = Offset.sequence(42)
    val secondOffset: Offset = Offset.sequence(98)
    val firstEvent           = SomeEvent(1L, "description")
    val secondEvent          = SomeEvent(2L, "description2")

    "store and retrieve failures for events" in {
      (projections: Projection[IO, SomeEvent],schemaManager: SchemaManager[IO]) =>
        val expected                            = Seq((firstEvent, firstOffset), (secondEvent, secondOffset))
        for {
          _     <- schemaManager.migrate()
          _   <- projections.recordFailure(id, persistenceId, 1L, firstOffset, firstEvent)
          _   <- projections.recordFailure(id, persistenceId, 2L, secondOffset, secondEvent)
          log <- projections.failures(id).compile.toVector
          _    = log should contain theSameElementsInOrderAs expected
        } yield ()
    }

    "retrieve no failures for an unknown projection" in {
      (projections: Projection[IO, SomeEvent],schemaManager: SchemaManager[IO]) =>
        for {
          _     <- schemaManager.migrate()
          log <- projections.failures(genString()).compile.toVector
          _    = log shouldBe empty
        } yield ()
    }
  }

}
