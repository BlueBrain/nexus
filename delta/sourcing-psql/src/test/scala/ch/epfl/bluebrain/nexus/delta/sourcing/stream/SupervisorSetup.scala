package ch.epfl.bluebrain.nexus.delta.sourcing.stream

import cats.effect.{Clock, IO, Resource}
import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategyConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.config.ProjectionConfig.ClusterConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.config.{BatchConfig, ProjectionConfig, QueryConfig}
import ch.epfl.bluebrain.nexus.delta.sourcing.postgres.Doobie
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.{ProjectionErrors, Projections}
import ch.epfl.bluebrain.nexus.delta.sourcing.query.RefreshStrategy
import ch.epfl.bluebrain.nexus.testkit.clock.FixedClock
import ch.epfl.bluebrain.nexus.testkit.mu.NexusSuite
import ch.epfl.bluebrain.nexus.testkit.mu.ce.ResourceFixture
import ch.epfl.bluebrain.nexus.testkit.mu.ce.ResourceFixture.IOFixture

import scala.concurrent.duration._

final case class SupervisorSetup(supervisor: Supervisor, projections: Projections, projectionErrors: ProjectionErrors)

object SupervisorSetup {

  val defaultQueryConfig: QueryConfig = QueryConfig(10, RefreshStrategy.Delay(10.millis))

  def unapply(setup: SupervisorSetup): (Supervisor, Projections, ProjectionErrors) =
    (setup.supervisor, setup.projections, setup.projectionErrors)

  def resource(
      cluster: ClusterConfig,
      clock: Clock[IO]
  ): Resource[IO, SupervisorSetup] = {
    val config: ProjectionConfig = ProjectionConfig(
      cluster,
      BatchConfig(3, 50.millis),
      RetryStrategyConfig.ConstantStrategyConfig(50.millis, 5),
      10.millis,
      10.millis,
      14.days,
      1.second,
      defaultQueryConfig
    )
    resource(config, clock)
  }

  def resource(
      config: ProjectionConfig,
      clock: Clock[IO]
  ): Resource[IO, SupervisorSetup] =
    Doobie.resource().flatMap { xas =>
      val projections      = Projections(xas, config.query, config.restartTtl, clock)
      val projectionErrors = ProjectionErrors(xas, config.query, clock)
      Supervisor(projections, projectionErrors, config).map(s => SupervisorSetup(s, projections, projectionErrors))
    }

  def suiteLocalFixture(name: String, cluster: ClusterConfig, clock: Clock[IO]): IOFixture[SupervisorSetup] =
    ResourceFixture.suiteLocal(name, resource(cluster, clock))

  trait Fixture { self: NexusSuite with FixedClock =>
    val supervisor: IOFixture[SupervisorSetup]    =
      SupervisorSetup.suiteLocalFixture("supervisor", ClusterConfig(1, 0), clock)
    val supervisor3_1: IOFixture[SupervisorSetup] =
      SupervisorSetup.suiteLocalFixture("supervisor3", ClusterConfig(3, 1), clock)
  }

}
