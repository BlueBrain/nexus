package ch.epfl.bluebrain.nexus.delta.sourcing.stream

import cats.effect.{Clock, IO, Resource}
import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategyConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.config.ProjectionConfig.ClusterConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.config.QueryConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.postgres.Doobie
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.{ProjectionErrors, Projections}
import ch.epfl.bluebrain.nexus.delta.sourcing.query.RefreshStrategy
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.config.{BatchConfig, ProjectionConfig}
import ch.epfl.bluebrain.nexus.testkit.clock.FixedClock
import ch.epfl.bluebrain.nexus.testkit.mu.NexusSuite
import munit.catseffect.IOFixture

import scala.concurrent.duration.*

final case class SupervisorSetup(supervisor: Supervisor, projections: Projections, projectionErrors: ProjectionErrors)

object SupervisorSetup {

  private val defaultQueryConfig: QueryConfig = QueryConfig(10, RefreshStrategy.Delay(10.millis))

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
      14.days,
      defaultQueryConfig
    )
    resource(config, clock)
  }

  def resource(
      config: ProjectionConfig,
      clock: Clock[IO]
  ): Resource[IO, SupervisorSetup] =
    Doobie.resourceDefault.flatMap { xas =>
      val projections      = Projections(xas, None, config.query, clock)
      val projectionErrors = ProjectionErrors(xas, config.query, clock)
      Supervisor(projections, projectionErrors, config).map(s => SupervisorSetup(s, projections, projectionErrors))
    }

  trait Fixture { self: NexusSuite with FixedClock =>

    private def suiteLocalFixture(name: String, cluster: ClusterConfig): IOFixture[SupervisorSetup] =
      ResourceSuiteLocalFixture(name, resource(cluster, clock))

    val supervisor: IOFixture[SupervisorSetup]    =
      suiteLocalFixture("supervisor", ClusterConfig(1, 0))
    val supervisor3_1: IOFixture[SupervisorSetup] =
      suiteLocalFixture("supervisor3", ClusterConfig(3, 1))
  }

}
