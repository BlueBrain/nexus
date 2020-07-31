package ch.epfl.bluebrain.nexus.sourcingnew.projections

import akka.actor.testkit.typed.scaladsl.ActorTestKit
import akka.actor.typed.ActorSystem
import akka.stream.alpakka.cassandra.CassandraSessionSettings
import akka.stream.alpakka.cassandra.scaladsl.{CassandraSession, CassandraSessionRegistry}
import cats.effect.{IO, Resource}
import cats.implicits._
import ch.epfl.bluebrain.nexus.sourcingnew.{Persistence, ProjectionModule}
import ch.epfl.bluebrain.nexus.sourcingnew.projections.cassandra.{CassandraConfig, ProjectionConfig}
import ch.epfl.bluebrain.nexus.testkit.cassandra.CassandraDocker
import ch.epfl.bluebrain.nexus.testkit.cassandra.CassandraDocker.CassandraHostConfig
import com.typesafe.config.ConfigFactory
import distage.ModuleDef
import izumi.distage.effect.modules.CatsDIEffectModule
import izumi.distage.model.definition.Activation
import izumi.distage.plugins.PluginConfig
import izumi.distage.testkit.TestConfig
import izumi.distage.testkit.TestConfig.ParallelLevel

import scala.concurrent.ExecutionContext

class CassandraProjectionSpec extends ProjectionSpec {

  private lazy val projectionConfig = ProjectionConfig(
    "projections", "projections_progress", "projections_failures"
  )

  private lazy val cassandraConfig = CassandraConfig(
    keyspaceAutoCreate = true,
    tablesAutoCreate = true,
    "'SimpleStrategy','replication_factor': 1",
    projectionConfig
  )

  override protected def config: TestConfig =
    TestConfig(
      pluginConfig = PluginConfig.empty,
      activation = Activation(Persistence -> Persistence.Cassandra),
      parallelTests = ParallelLevel.Sequential,
      moduleOverrides = new ModuleDef {
        include(CatsDIEffectModule)
        include(new CassandraDocker.Module[IO])
        include(new ProjectionModule[IO, SomeEvent])
        make[ProjectionConfig].fromValue(projectionConfig)
        make[CassandraConfig].fromValue(cassandraConfig)
        make[ActorSystem[Nothing]].fromResource {
          host: CassandraHostConfig =>
            val config = ConfigFactory
              .parseString(s"""datastax-java-driver.basic.contact-points = ["${host.host}:${host.port}"]""")
              .withFallback(ConfigFactory.parseResources("cassandra.conf"))
              .withFallback(ConfigFactory.load())
              .resolve()
            Resource.make(
              IO.delay(ActorTestKit("ProjectionsSpec", config).system)) { system =>
              IO(system.terminate()) >> IO.unit
            }
        }
        make[CassandraSession].fromResource { system: ActorSystem[Nothing] =>
          Resource.make(
            IO.delay {
              CassandraSessionRegistry
                .get(system).sessionFor(CassandraSessionSettings(CassandraConfig.defaultPath))
            }
          ){ session =>
            IO.fromFuture(
              IO(session.close(ExecutionContext.global))
            ) >> IO.unit
          }
        }
      },
      configBaseName = "cassandra-projections-test"
    )
}
