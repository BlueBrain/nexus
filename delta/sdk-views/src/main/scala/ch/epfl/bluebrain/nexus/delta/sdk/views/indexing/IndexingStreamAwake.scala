package ch.epfl.bluebrain.nexus.delta.sdk.views.indexing

import akka.actor.typed.ActorSystem
import akka.persistence.query.Offset
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.kernel.{RetryStrategy, RetryStrategyConfig}
import ch.epfl.bluebrain.nexus.delta.sdk.model.Event.ProjectScopedEvent
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Envelope, Event, ResourceRef}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sourcing.EventLog
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.stream.DaemonStreamCoordinator
import com.typesafe.scalalogging.Logger
import fs2.Stream
import monix.bio.Task
import monix.execution.Scheduler

import java.time.Instant
import scala.concurrent.duration.FiniteDuration
import scala.reflect.ClassTag

object IndexingStreamAwake {

  private val logger: Logger = Logger[IndexingStreamAwake.type]

  type CurrentViews = ProjectRef => Task[Seq[ResourceRef.Revision]]

  /**
    * Starts a coordinator that holds an infinite stream. This stream reads from the primary store all the events
    * and computes the diff in time between consecutive events in the same project.
    * If the distance is greater then the ''idleTimeout'', all the views' indexing streams are awaken.
    */
  def start[V](
      eventLog: EventLog[Envelope[ProjectScopedEvent]],
      coordinator: IndexingStreamCoordinator[V],
      views: CurrentViews,
      idleTimeout: FiniteDuration,
      retry: RetryStrategyConfig
  )(implicit uuidF: UUIDF, as: ActorSystem[Nothing], scheduler: Scheduler, V: ClassTag[V]): Task[Unit] = {
    val s    = stream(eventLog, coordinator, views, idleTimeout)
    val name = s"IndexingStreamAwakeScan${V.simpleName}"
    DaemonStreamCoordinator.run(name, s, RetryStrategy.retryOnNonFatal(retry, logger, name))
  }

  private[indexing] def stream[V](
      eventLog: EventLog[Envelope[ProjectScopedEvent]],
      coordinator: IndexingStreamCoordinator[V],
      views: CurrentViews,
      idleTimeout: FiniteDuration
  ): Stream[Task, Unit] =
    eventLog
      .eventsByTag(Event.eventTag, Offset.noOffset)
      .evalScan(Map.empty[ProjectRef, Instant]) { (acc, env) =>
        val event       = env.event
        val prevInstant = acc.getOrElse(event.project, event.instant)
        Task.when(event.instant.diff(prevInstant).gteq(idleTimeout)) {
          views(event.project).flatMap { viewIds =>
            Task.parTraverse(viewIds)(viewId => coordinator.run(viewId.iri, event.project, viewId.rev)).void
          }
        } >> Task.pure(acc + (event.project -> event.instant))
      }
      .void
}
