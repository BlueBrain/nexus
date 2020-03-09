package ch.epfl.bluebrain.nexus.cli.postgres

import cats.effect.{ContextShift, Effect, IO, Timer}
import ch.epfl.bluebrain.nexus.cli.postgres.config.PostgresConfig
import com.github.ghik.silencer.silent
import doobie.util.transactor.Transactor
import izumi.distage.model.definition.ModuleDef
import izumi.distage.plugins.PluginConfig
import izumi.distage.testkit.TestConfig
import izumi.distage.testkit.scalatest.DistageSpecScalatest

import scala.concurrent.ExecutionContext

class ProjectionSpec extends DistageSpecScalatest[IO] {

  private implicit val cs: ContextShift[IO] = IO.contextShift(ExecutionContext.global)
  private implicit val tm: Timer[IO]        = IO.timer(ExecutionContext.global)

  @silent
  override def config: TestConfig = TestConfig(
    pluginConfig = PluginConfig.const(IOPlugin),
    moduleOverrides = new ModuleDef {
      make[Transactor[IO]].from { (cfg: PostgresConfig) =>
        Transactor.fromDriverManager[IO](
          "org.postgresql.Driver",                               // driver classname
          s"jdbc:postgresql://${cfg.host}:${cfg.port}/postgres", // connect URL (driver-specific)
          "postgres",                                            // user
          "postgres"                                             // password
        )
      }
      include(new PostgresDocker.Module[IO])
    },
    configBaseName = "postgres-test"
  )

  "A DB" should {
    "select 1" in {
      import doobie.implicits._
      (xa: Transactor[IO], eff: Effect[IO]) => {
        implicit val e = eff
        for {
          result <- sql"select 1;".query[Int].unique.transact(xa)
          _ = assert(result == 1)
        } yield ()
      }
    }
  }

}
