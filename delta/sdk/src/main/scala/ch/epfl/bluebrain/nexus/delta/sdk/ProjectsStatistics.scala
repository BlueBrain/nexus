package ch.epfl.bluebrain.nexus.delta.sdk

import akka.actor.typed.ActorSystem
import akka.persistence.query.Offset
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.kernel.{IndexingConfig, RetryStrategy}
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectStatisticsCollection.ProjectStatistics
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{ProjectRef, ProjectStatisticsCollection, ProjectsConfig}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Envelope, Event}
import ch.epfl.bluebrain.nexus.sourcing.config.PersistProgressConfig
import ch.epfl.bluebrain.nexus.sourcing.projections.ProjectionId.CacheProjectionId
import ch.epfl.bluebrain.nexus.sourcing.projections.ProjectionStream._
import ch.epfl.bluebrain.nexus.sourcing.projections.stream.StatefulStreamSupervisor
import ch.epfl.bluebrain.nexus.sourcing.projections.{Projection, ProjectionProgress, SuccessMessage}
import com.typesafe.scalalogging.Logger
import fs2.Stream
import monix.bio.Task
import monix.execution.Scheduler
import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategy.logError

trait ProjectsStatistics {

  /**
    * Retrieve the current statistics in terms of number of existing events and the latest offset (per project)
    */
  def get(): Task[ProjectStatisticsCollection]

  /**
    * Retrieve the current statistics in terms of number of existing events and the latest offset for the passed ''projectRef''
    */
  def getFor(projectRef: ProjectRef): Task[Option[ProjectStatistics]] =
    get().map(_.get(projectRef))
}

object ProjectsStatistics {
  private val logger: Logger = Logger[ProjectsStatistics]
  private type StreamFromOffset = Offset => Stream[Task, Envelope[Event]]
  implicit private[sdk] val projectionId: CacheProjectionId = CacheProjectionId("ProjectsStatistics")

  /**
    * Construct a [[ProjectsStatistics]] from a passed ''projection'' and ''stream'' function.
    * The underlying stream will store its progress nd compute the statistics for each project (counts and latest offset)
    */
  def apply(
      config: ProjectsConfig,
      projection: Projection[ProjectStatisticsCollection],
      stream: StreamFromOffset
  )(implicit as: ActorSystem[Nothing], sc: Scheduler): Task[ProjectsStatistics] =
    apply(config.indexing, config.persistProgressConfig, projection, stream)

  private[sdk] def apply(
      indexing: IndexingConfig,
      persistProgressConfig: PersistProgressConfig,
      projection: Projection[ProjectStatisticsCollection],
      stream: StreamFromOffset
  )(implicit as: ActorSystem[Nothing], sc: Scheduler): Task[ProjectsStatistics] = {

    def buildStream(progress: ProjectionProgress): Stream[Task, ProjectStatisticsCollection] =
      stream(progress.offset)
        .mapFilter(env => env.event.belongsTo.map(env.toMessage.as))
        .scan[Option[SuccessMessage[ProjectStatisticsCollection]]](None) { (accOpt, msg) =>
          Some(msg.as(accOpt.fold(ProjectStatisticsCollection.empty)(_.value).incrementCount(msg.value, msg.offset)))
        }
        .mapFilter(identity)
        .persistProgress(progress, projection, persistProgressConfig)

    val retryStrategy = RetryStrategy[Throwable](indexing.retry, _ => true, logError(logger, "projects statistics"))

    for {
      progress   <- projection.progress(projectionId)
      stream      = Task.delay(buildStream(progress))
      supervisor <- StatefulStreamSupervisor("ProjectsStatistics", stream, retryStrategy)
    } yield new ProjectsStatistics {
      override def get(): Task[ProjectStatisticsCollection] = supervisor.state
    }
  }
}
