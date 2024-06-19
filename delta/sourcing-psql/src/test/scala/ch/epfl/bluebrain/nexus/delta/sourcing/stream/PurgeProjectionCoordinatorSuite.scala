package ch.epfl.bluebrain.nexus.delta.sourcing.stream

import cats.effect.IO
import cats.effect.kernel.Ref
import ch.epfl.bluebrain.nexus.delta.sourcing.config.PurgeConfig
import ch.epfl.bluebrain.nexus.testkit.mu.NexusSuite
import ch.epfl.bluebrain.nexus.testkit.mu.ce.PatienceConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.PurgeProjectionCoordinator.PurgeProjection
import munit.AnyFixture

import java.time.Instant
import scala.concurrent.duration._

class PurgeProjectionCoordinatorSuite extends NexusSuite with SupervisorSetup.Fixture {

  override def munitFixtures: Seq[AnyFixture[_]] = List(supervisor)

  implicit private val patienceConfig: PatienceConfig = PatienceConfig(5.seconds, 10.millis)

  private lazy val sv = supervisor().supervisor

  test("Schedule and have the supervisor executing the given purge projection") {
    val metadata        = ProjectionMetadata("test", "purge")
    val config          = PurgeConfig(100.millis, 5.days)
    val expectedInstant = Instant.EPOCH.minusMillis(config.ttl.toMillis)

    for {
      ref            <- Ref.of[IO, Instant](Instant.MIN)
      purgeProjection = PurgeProjection(metadata, config, ref.set)
      _              <- PurgeProjectionCoordinator(sv, clock, Set(purgeProjection))
      _              <- sv.describe(metadata.name)
                          .map(_.map(_.status))
                          .assertEquals(Some(ExecutionStatus.Running))
                          .eventually
      _              <- ref.get.assertEquals(expectedInstant).eventually
    } yield ()
  }
}
