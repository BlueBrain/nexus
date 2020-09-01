package ch.epfl.bluebrain.nexus.sourcing.projections

import akka.actor.ActorSystem
import akka.persistence.query.Offset
import akka.stream.scaladsl.Source
import akka.stream.{Materializer, SystemMaterializer}
import cats.effect.{ContextShift, IO, Resource, Timer}
import cats.implicits._
import ch.epfl.bluebrain.nexus.sourcing.{CassandraDocker, CassandraDockerModule}
import ch.epfl.bluebrain.nexus.sourcing.projections.ProjectionProgress.{NoProgress, OffsetProgress}
import ch.epfl.bluebrain.nexus.sourcing.projections.ProjectionsSpec.SomeEvent
import com.typesafe.config.ConfigFactory
import distage.{DIKey, ModuleDef}
import io.circe.{Decoder, Encoder}
import izumi.distage.docker.Docker
import izumi.distage.docker.modules.DockerSupportModule
import izumi.distage.effect.modules.CatsDIEffectModule
import izumi.distage.model.definition.StandardAxis
import izumi.distage.plugins.PluginConfig
import izumi.distage.testkit.TestConfig
import izumi.distage.testkit.TestConfig.ParallelLevel
import izumi.distage.testkit.scalatest.DistageSpecScalatest
import org.scalatest.matchers.should.Matchers.{contain, empty}

import scala.concurrent.ExecutionContext

class ProjectionsSpec extends DistageSpecScalatest[IO] with TestHelpers with ShouldMatchers {

  implicit protected val cs: ContextShift[IO] = IO.contextShift(ExecutionContext.global)
  implicit protected val tm: Timer[IO]        = IO.timer(ExecutionContext.global)

  override protected def config: TestConfig =
    TestConfig(
      pluginConfig = PluginConfig.empty,
      activation = StandardAxis.testDummyActivation,
      parallelTests = ParallelLevel.Sequential,
      memoizationRoots = Set(DIKey[Projections[IO, SomeEvent]], DIKey[ActorSystem], DIKey[Materializer]),
      moduleOverrides = new ModuleDef {
        // add docker dependencies and override default configuration
        include(new DockerSupportModule[IO] overridenBy new ModuleDef {
          make[Docker.ClientConfig].from {
            Docker.ClientConfig(
              readTimeoutMs = 60000, // long timeout for gh actions
              connectTimeoutMs = 500,
              allowReuse = true,
              useRemote = false,
              useRegistry = true,
              remote = None,
              registry = None
            )
          }
        })
        include(CatsDIEffectModule)
        include(CassandraDockerModule[IO])
        make[ActorSystem].fromResource {
          c: CassandraDocker.Container =>
            val port   = c.availablePorts.first(CassandraDocker.primaryPort)
            val config = ConfigFactory
              .parseString(s"""datastax-java-driver.basic.contact-points = ["${port.hostV4}:${port.port}"]""")
              .withFallback(ConfigFactory.parseResources("cassandra.conf"))
              .withFallback(ConfigFactory.load())
              .resolve()
            Resource.make(IO.delay(ActorSystem("ProjectionsSpec", config))) { system =>
              IO.fromFuture(IO(system.terminate())) >> IO.unit
            }
        }
        make[Materializer].from { as: ActorSystem =>
          SystemMaterializer(as).materializer
        }
        make[Projections[IO, SomeEvent]].fromEffect { implicit as: ActorSystem =>
          Projections[IO, SomeEvent]
        }
      },
      configBaseName = "projections-test"
    )

  "A Projection" should {
    val id            = genString()
    val persistenceId = s"/some/${genString()}"
    val progress      = OffsetProgress(Offset.sequence(42), 42, 42, 0)

    "store and retrieve progress" in { projections: Projections[IO, SomeEvent] =>
      for {
        _    <- projections.recordProgress(id, progress)
        read <- projections.progress(id)
        _     = read shouldEqual progress
      } yield ()
    }

    "retrieve NoProgress for unknown projections" in { projections: Projections[IO, SomeEvent] =>
      for {
        read <- projections.progress(genString())
        _     = read shouldEqual NoProgress
      } yield ()
    }

    val firstOffset: Offset  = Offset.sequence(42)
    val secondOffset: Offset = Offset.sequence(98)
    val firstEvent           = SomeEvent(1L, "description")
    val secondEvent          = SomeEvent(2L, "description2")

    "store and retrieve failures for events" in { (projections: Projections[IO, SomeEvent], m: Materializer) =>
      implicit val materializer: Materializer = m
      val expected                            = Seq((firstEvent, firstOffset), (secondEvent, secondOffset))
      for {
        _   <- projections.recordFailure(id, persistenceId, 1L, firstOffset, firstEvent)
        _   <- projections.recordFailure(id, persistenceId, 2L, secondOffset, secondEvent)
        log <- logOf(projections.failures(id))
        _    = log should contain theSameElementsInOrderAs expected
      } yield ()
    }

    "retrieve no failures for an unknown projection" in { (projections: Projections[IO, SomeEvent], m: Materializer) =>
      implicit val materializer: Materializer = m
      for {
        log <- logOf(projections.failures(genString()))
        _    = log shouldBe empty
      } yield ()
    }

  }

  private def logOf(
      source: Source[(SomeEvent, Offset), _]
  )(implicit mat: Materializer): IO[Vector[(SomeEvent, Offset)]] = {
    val f = source.runFold(Vector.empty[(SomeEvent, Offset)])(_ :+ _)
    IO.fromFuture(IO(f))
  }
}

object ProjectionsSpec {
  final case class SomeEvent(rev: Long, description: String)
  object SomeEvent {
    import io.circe.generic.semiauto._
    implicit final val encoder: Encoder[SomeEvent] = deriveEncoder[SomeEvent]
    implicit final val decoder: Decoder[SomeEvent] = deriveDecoder[SomeEvent]
  }
}
