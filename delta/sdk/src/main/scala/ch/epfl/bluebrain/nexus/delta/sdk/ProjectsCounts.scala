package ch.epfl.bluebrain.nexus.delta.sdk

import akka.actor.typed.ActorSystem
import akka.persistence.query.Offset
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategy
import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategy.logError
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.sdk.cache.{KeyValueStore, KeyValueStoreConfig}
import ch.epfl.bluebrain.nexus.delta.sdk.model.Event.ProjectScopedEvent
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectCountsCollection.ProjectCount
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{ProjectCountsCollection, ProjectRef, ProjectsConfig}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Envelope, Event}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sourcing.config.SaveProgressConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.ProjectionId.CacheProjectionId
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.stream.DaemonStreamCoordinator
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.{Projection, SuccessMessage}
import com.typesafe.scalalogging.Logger
import fs2.Stream
import monix.bio.{Task, UIO}
import monix.execution.Scheduler

trait ProjectsCounts {

  /**
    * Retrieve the current counts (and instant) of events for all projects
    */
  def get(): UIO[ProjectCountsCollection]

  /**
    * Retrieve the current counts (and latest instant) of events for the passed ''project''
    */
  def get(project: ProjectRef): UIO[Option[ProjectCount]]
}

object ProjectsCounts {
  private val logger: Logger = Logger[ProjectsCounts]
  private type StreamFromOffset = Offset => Stream[Task, Envelope[Event]]
  private[sdk] val projectionId: CacheProjectionId = CacheProjectionId("ProjectsCounts")

  /**
    * Construct a [[ProjectsCounts]] from a passed ''projection'' and ''stream'' function.
    * The underlying stream will store its progress and compute the counts (and latest instant) for each project.
    */
  def apply(
      config: ProjectsConfig,
      projection: Projection[ProjectCountsCollection],
      stream: StreamFromOffset
  )(implicit uuidF: UUIDF, as: ActorSystem[Nothing], sc: Scheduler): Task[ProjectsCounts] =
    apply(projection, stream)(uuidF, config.keyValueStore, config.persistProgressConfig, as, sc)

  private[sdk] def apply(
      projection: Projection[ProjectCountsCollection],
      stream: StreamFromOffset
  )(implicit
      uuidF: UUIDF,
      keyValueStoreConfig: KeyValueStoreConfig,
      persistProgressConfig: SaveProgressConfig,
      as: ActorSystem[Nothing],
      sc: Scheduler
  ): Task[ProjectsCounts] = {

    val cache =
      KeyValueStore.distributed[ProjectRef, ProjectCount]("ProjectsCounts", (_, stats) => stats.value)

    def buildStream: Stream[Task, Unit] =
      Stream
        .eval(projection.progress(projectionId))
        .evalTap { progress =>
          cache.putAll(progress.value.value)
        }
        .flatMap { progress =>
          val initial = SuccessMessage(progress.offset, progress.timestamp, "", 1, progress.value, Vector.empty)
          stream(progress.offset)
            .collect { case env @ Envelope(event: ProjectScopedEvent, _, _, _, _, _) =>
              env.toMessage.as(event.project)
            }
            .mapAccumulate(initial) { (acc, msg) =>
              (msg.as(acc.value.increment(msg.value, msg.timestamp)), msg.value)
            }
            .evalMap { case (acc, projectRef) =>
              cache.put(projectRef, acc.value.value(projectRef)).as(acc)
            }
            .persistProgress(progress, projectionId, projection, persistProgressConfig)
            .void
        }

    val retryStrategy =
      RetryStrategy[Throwable](keyValueStoreConfig.retry, _ => true, logError(logger, "projects counts"))

    DaemonStreamCoordinator
      .run("ProjectsCounts", buildStream, retryStrategy)
      .as(
        new ProjectsCounts {

          override def get(): UIO[ProjectCountsCollection] = cache.entries.map(ProjectCountsCollection(_))

          override def get(project: ProjectRef): UIO[Option[ProjectCount]] = cache.get(project)
        }
      )
  }
}
