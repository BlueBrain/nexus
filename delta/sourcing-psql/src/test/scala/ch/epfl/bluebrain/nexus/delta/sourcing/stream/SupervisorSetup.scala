package ch.epfl.bluebrain.nexus.delta.sourcing.stream

import cats.effect.{Clock, Resource}
import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategyConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.config.ProjectionConfig.{ClusterConfig, ProgressConfig}
import ch.epfl.bluebrain.nexus.delta.sourcing.config.{ProjectionConfig, QueryConfig}
import ch.epfl.bluebrain.nexus.delta.sourcing.query.RefreshStrategy
import ch.epfl.bluebrain.nexus.testkit.bio.{BioSuite, ResourceFixture}
import ch.epfl.bluebrain.nexus.testkit.postgres.Doobie
import monix.bio.{Task, UIO}
import monix.execution.Scheduler

import scala.concurrent.duration._

object SupervisorSetup {

  val defaultQueryConfig: QueryConfig = QueryConfig(10, RefreshStrategy.Stop)

  val defaultProjectionConfig: ProjectionConfig = ProjectionConfig(
    ClusterConfig(3, 1),
    ProgressConfig(3, 10.millis),
    RetryStrategyConfig.AlwaysGiveUp,
    10.millis,
    defaultQueryConfig
  )

  def resource()(implicit clock: Clock[UIO], s: Scheduler, cl: ClassLoader): Resource[Task, (Supervisor, ProjectionStore)] =
    resource(defaultProjectionConfig)

  def resource(config: ProjectionConfig)(implicit clock: Clock[UIO], s: Scheduler, cl: ClassLoader): Resource[Task, (Supervisor, ProjectionStore)] =
    Doobie.resource().flatMap { xas =>
      val projectionStore = ProjectionStore(xas, config.query)
      Resource.make(
        Supervisor(projectionStore, config).map(_ -> projectionStore)
      )(s => s._1.stop())
    }

  def suiteLocalFixture(name: String)(implicit clock: Clock[UIO], s: Scheduler, cl: ClassLoader): ResourceFixture.TaskFixture[(Supervisor, ProjectionStore)] =
    ResourceFixture.suiteLocal(name, resource())

  trait Fixture { self: BioSuite =>
    val supervisor: ResourceFixture.TaskFixture[(Supervisor, ProjectionStore)] = SupervisorSetup.suiteLocalFixture("supervisor")
  }

}
