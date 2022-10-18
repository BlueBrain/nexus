package ch.epfl.bluebrain.nexus.delta.sourcing.stream

import cats.effect.{Clock, Resource}
import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategyConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.config.ProjectionConfig.ClusterConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.config.{BatchConfig, ProjectionConfig, QueryConfig}
import ch.epfl.bluebrain.nexus.delta.sourcing.query.RefreshStrategy
import ch.epfl.bluebrain.nexus.testkit.bio.{BioSuite, ResourceFixture}
import ch.epfl.bluebrain.nexus.testkit.postgres.Doobie
import monix.bio.{Task, UIO}
import monix.execution.Scheduler

import scala.concurrent.duration._

object SupervisorSetup {

  val defaultQueryConfig: QueryConfig = QueryConfig(10, RefreshStrategy.Stop)

  def resource(cluster: ClusterConfig)(implicit clock: Clock[UIO], s: Scheduler, cl: ClassLoader): Resource[Task, (Supervisor, ProjectionStore)] = {
    val config: ProjectionConfig = ProjectionConfig(
      cluster,
      BatchConfig(3, 50.millis),
      RetryStrategyConfig.AlwaysGiveUp,
      10.millis,
      defaultQueryConfig
    )
    resource(config)
  }

  def resource(config: ProjectionConfig)(implicit clock: Clock[UIO], s: Scheduler, cl: ClassLoader): Resource[Task, (Supervisor, ProjectionStore)] =
    Doobie.resource().flatMap { xas =>
      val projectionStore = ProjectionStore(xas, config.query)
      Resource.make(
        Supervisor(projectionStore, config).map(_ -> projectionStore)
      )(s => s._1.stop())
    }

  def suiteLocalFixture(name: String, cluster: ClusterConfig)(implicit clock: Clock[UIO], s: Scheduler, cl: ClassLoader): ResourceFixture.TaskFixture[(Supervisor, ProjectionStore)] =
    ResourceFixture.suiteLocal(name, resource(cluster))

  trait Fixture { self: BioSuite =>
    val supervisor: ResourceFixture.TaskFixture[(Supervisor, ProjectionStore)] = SupervisorSetup.suiteLocalFixture("supervisor", ClusterConfig(1, 0))
    val supervisor3_1: ResourceFixture.TaskFixture[(Supervisor, ProjectionStore)] = SupervisorSetup.suiteLocalFixture("supervisor3", ClusterConfig(3, 1))
  }

}
