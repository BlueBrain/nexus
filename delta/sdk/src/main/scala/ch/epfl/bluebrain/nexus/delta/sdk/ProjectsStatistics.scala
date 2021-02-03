package ch.epfl.bluebrain.nexus.delta.sdk

import akka.actor.typed.ActorSystem
import akka.persistence.query.Offset
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategy
import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategy.logError
import ch.epfl.bluebrain.nexus.delta.sdk.cache.{KeyValueStore, KeyValueStoreConfig}
import ch.epfl.bluebrain.nexus.delta.sdk.model.Event.ProjectScopedEvent
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectStatisticsCollection.ProjectStatistics
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{ProjectRef, ProjectStatisticsCollection, ProjectsConfig}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Envelope, Event}
import ch.epfl.bluebrain.nexus.sourcing.config.PersistProgressConfig
import ch.epfl.bluebrain.nexus.sourcing.projections.ProjectionId.CacheProjectionId
import ch.epfl.bluebrain.nexus.sourcing.projections.ProjectionStream._
import ch.epfl.bluebrain.nexus.sourcing.projections.stream.StreamSupervisor
import ch.epfl.bluebrain.nexus.sourcing.projections.{Projection, ProjectionProgress, SuccessMessage}
import com.typesafe.scalalogging.Logger
import fs2.Stream
import monix.bio.{Task, UIO}
import monix.execution.Scheduler

trait ProjectsStatistics {

  /**
    * Retrieve the current statistics in terms of number of existing events and the latest offset (per project)
    */
  def get(): UIO[ProjectStatisticsCollection]

  /**
    * Retrieve the current statistics in terms of number of existing events and the latest offset for the passed ''projectRef''
    */
  def getFor(projectRef: ProjectRef): UIO[Option[ProjectStatistics]]
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
    apply(projection, stream)(config.keyValueStore, config.persistProgressConfig, as, sc)

  private[sdk] def apply(
      projection: Projection[ProjectStatisticsCollection],
      stream: StreamFromOffset
  )(implicit
      keyValueStoreConfig: KeyValueStoreConfig,
      persistProgressConfig: PersistProgressConfig,
      as: ActorSystem[Nothing],
      sc: Scheduler
  ): Task[ProjectsStatistics] = {

    val cache =
      KeyValueStore.distributed[ProjectRef, ProjectStatistics]("ProjectsStatistics", (_, stats) => stats.count)

    def buildStream(
        progress: ProjectionProgress[ProjectStatisticsCollection]
    ): Stream[Task, ProjectStatisticsCollection] = {
      val initial = SuccessMessage(progress.offset, "", 1, progress.value, Vector.empty)
      stream(progress.offset)
        .collect { case env @ Envelope(event: ProjectScopedEvent, _, _, _, _, _) => env.toMessage.as(event.project) }
        .mapAccumulate(initial) { (acc, msg) => (msg.as(acc.value.incrementCount(msg.value, msg.offset)), msg.value) }
        .evalMap { case (acc, projectRef) => cache.put(projectRef, acc.value.value(projectRef)).as(acc) }
        .persistProgress(progress, projection, persistProgressConfig)
    }

    val retryStrategy =
      RetryStrategy[Throwable](keyValueStoreConfig.retry, _ => true, logError(logger, "projects statistics"))

    for {
      progress <- projection.progress(projectionId)
      _        <- cache.putAll(progress.value.value)
      stream    = Task.delay(buildStream(progress))
      _        <- StreamSupervisor("ProjectsStatistics", stream, retryStrategy)
    } yield new ProjectsStatistics {

      override def get(): UIO[ProjectStatisticsCollection] = cache.entries.map(ProjectStatisticsCollection(_))

      override def getFor(projectRef: ProjectRef): UIO[Option[ProjectStatistics]] = cache.get(projectRef)
    }
  }
}
