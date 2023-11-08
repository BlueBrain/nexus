package ch.epfl.bluebrain.nexus.delta.sourcing.stream

import cats.effect.{Clock, ContextShift, IO, Resource, Timer}
import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategyConfig
import ch.epfl.bluebrain.nexus.delta.kernel.effect.migration.ioToTaskK
import ch.epfl.bluebrain.nexus.delta.sourcing.config.ProjectionConfig.ClusterConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.config.{BatchConfig, ProjectionConfig, QueryConfig}
import ch.epfl.bluebrain.nexus.delta.sourcing.postgres.Doobie
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.{ProjectionErrors, Projections}
import ch.epfl.bluebrain.nexus.delta.sourcing.query.RefreshStrategy
import ch.epfl.bluebrain.nexus.testkit.bio.IOFixedClock
import ch.epfl.bluebrain.nexus.testkit.ce.CatsRunContext
import ch.epfl.bluebrain.nexus.testkit.mu.NexusSuite
import ch.epfl.bluebrain.nexus.testkit.mu.bio.ResourceFixture

import scala.concurrent.duration._

final case class SupervisorSetup(supervisor: Supervisor, projections: Projections, projectionErrors: ProjectionErrors)

object SupervisorSetup {

  val defaultQueryConfig: QueryConfig = QueryConfig(10, RefreshStrategy.Delay(10.millis))

  def unapply(setup: SupervisorSetup): (Supervisor, Projections, ProjectionErrors) =
    (setup.supervisor, setup.projections, setup.projectionErrors)

  def resource(
      cluster: ClusterConfig
  )(implicit
      clock: Clock[IO],
      timer: Timer[IO],
      cs: ContextShift[IO],
      cl: ClassLoader
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
    resource(config)
  }

  def resource(
      config: ProjectionConfig
  )(implicit clock: Clock[IO], timer: Timer[IO], cs: ContextShift[IO], cl: ClassLoader): Resource[IO, SupervisorSetup] =
    Doobie.resource().flatMap { xas =>
      val projections      = Projections(xas, config.query, config.restartTtl)
      val projectionErrors = ProjectionErrors(xas, config.query)
      Supervisor(projections, projectionErrors, config).map(s => SupervisorSetup(s, projections, projectionErrors))
    }

  def suiteLocalFixture(name: String, cluster: ClusterConfig)(implicit
      clock: Clock[IO],
      timer: Timer[IO],
      cs: ContextShift[IO],
      cl: ClassLoader
  ): ResourceFixture.TaskFixture[SupervisorSetup] =
    ResourceFixture.suiteLocal(name, resource(cluster).mapK(ioToTaskK))

  trait Fixture { self: NexusSuite with CatsRunContext with IOFixedClock =>
    val supervisor: ResourceFixture.TaskFixture[SupervisorSetup]    =
      SupervisorSetup.suiteLocalFixture("supervisor", ClusterConfig(1, 0))
    val supervisor3_1: ResourceFixture.TaskFixture[SupervisorSetup] =
      SupervisorSetup.suiteLocalFixture("supervisor3", ClusterConfig(3, 1))
  }

}
