package ch.epfl.bluebrain.nexus.delta.sourcing.stream

import cats.effect.{Clock, Resource}
import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategyConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.config.ProjectionConfig.ClusterConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.config.{BatchConfig, ProjectionConfig, QueryConfig}
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.Projections
import ch.epfl.bluebrain.nexus.delta.sourcing.query.RefreshStrategy
import ch.epfl.bluebrain.nexus.testkit.bio.{BioSuite, ResourceFixture}
import ch.epfl.bluebrain.nexus.testkit.postgres.Doobie
import monix.bio.{Task, UIO}

import scala.concurrent.duration._

object SupervisorSetup {

  val defaultQueryConfig: QueryConfig = QueryConfig(10, RefreshStrategy.Delay(10.millis))

  def resource(
      cluster: ClusterConfig
  )(implicit clock: Clock[UIO], cl: ClassLoader): Resource[Task, (Supervisor, Projections)] = {
    val config: ProjectionConfig = ProjectionConfig(
      cluster,
      BatchConfig(3, 50.millis),
      RetryStrategyConfig.AlwaysGiveUp,
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
  )(implicit clock: Clock[UIO], cl: ClassLoader): Resource[Task, (Supervisor, Projections)] =
    Doobie.resource().flatMap { xas =>
      val projections = Projections(xas, config.query, config.restartTtl)
      Supervisor(projections, config).map(_ -> projections)
    }

  def suiteLocalFixture(name: String, cluster: ClusterConfig)(implicit
      clock: Clock[UIO],
      cl: ClassLoader
  ): ResourceFixture.TaskFixture[(Supervisor, Projections)] =
    ResourceFixture.suiteLocal(name, resource(cluster))

  trait Fixture { self: BioSuite =>
    val supervisor: ResourceFixture.TaskFixture[(Supervisor, Projections)]    =
      SupervisorSetup.suiteLocalFixture("supervisor", ClusterConfig(1, 0))
    val supervisor3_1: ResourceFixture.TaskFixture[(Supervisor, Projections)] =
      SupervisorSetup.suiteLocalFixture("supervisor3", ClusterConfig(3, 1))
  }

}
