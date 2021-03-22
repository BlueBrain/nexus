package ch.epfl.bluebrain.nexus.delta.sdk.indexing

import akka.actor.typed._
import akka.actor.typed.scaladsl.Behaviors
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import akka.cluster.sharding.typed.scaladsl.ClusterSharding.ShardCommand
import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategy
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.views.ViewIndex
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.stream.StreamSwitch
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.{Projection, ProjectionProgress}
import com.typesafe.scalalogging.Logger
import fs2.Stream
import monix.bio.{Task, UIO}
import monix.execution.Scheduler

import java.util.UUID
import scala.util.{Failure, Success}

/**
  * The view index behavior defined in order to manage the indexing stream
  */
object IndexingStreamBehaviour {

  type IndexingStream[V] = (ViewIndex[V], ProjectionProgress[Unit]) => Task[Stream[Task, Unit]]

  type ClearIndex = String => Task[Unit]

  private val logger: Logger = Logger[IndexingStreamBehaviour.type]

  /**
    * Creates a behavior for a StreamSupervisor that manages the stream
    */
  def apply[V](
      shard: ActorRef[ShardCommand],
      project: ProjectRef,
      id: Iri,
      fetchView: (Iri, ProjectRef) => UIO[Option[ViewIndex[V]]],
      buildStream: IndexingStream[V],
      clearIndex: ClearIndex,
      projection: Projection[Unit],
      retryStrategy: RetryStrategy[Throwable]
  )(implicit uuidF: UUIDF, sc: Scheduler): Behavior[IndexingViewCommand[V]] =
    Behaviors.setup[IndexingViewCommand[V]] { context =>
      Behaviors.withStash(100) { buffer =>
        import context._

        def streamName(rev: Long) = s"${project}_${id}_$rev"

        // When the stream completes or receives an error, we stop the actor
        def onFinalize(uuid: UUID) = UIO.delay(self ! StreamStopped(uuid))

        def runStream(current: Option[(ViewIndex[V], StreamSwitch)]): Unit = {
          val io: Task[IndexingViewCommand[V]] = fetchView(id, project).flatMap {
            case None                                    =>
              UIO.delay(
                logger.info("View {} in project {} can't be found, the indexing will be passivated", id, project)
              ) >> current.fold(Task.unit)(_._2.stop).as(ViewNotFound)
            case Some(fetchView) if fetchView.deprecated =>
              UIO.delay(
                logger.info("View {} in project {} is deprecated, the indexing will be passivated", id, project)
              ) >> current.fold(Task.unit)(_._2.stop).as(ViewIsDeprecated)
            case Some(fetchView)                         =>
              for {
                progress <- projection.progress(fetchView.projectionId)
                switch   <-
                  current match {
                    case None                                                             =>
                      UIO.delay(
                        logger
                          .debug(
                            "Index view {} with revision {} in project {} will start from offset {}",
                            id,
                            fetchView.rev,
                            project,
                            progress.offset
                          )
                      ) >>
                        StreamSwitch.run(
                          streamName(fetchView.rev),
                          buildStream(fetchView, progress),
                          retryStrategy,
                          onFinalize,
                          onFinalize
                        )
                    case Some((currentIndex, switch)) if fetchView.rev > currentIndex.rev =>
                      UIO.delay(
                        logger.debug(
                          "New revision of view {} in project {} is detected, the indexing will be restart from the beginning",
                          fetchView.rev,
                          id,
                          project
                        )
                      ) >>
                        switch.stop >> clearIndex(currentIndex.index) >> restartStream(fetchView)
                    case Some((_, switch))                                                =>
                      UIO.delay(
                        logger.debug(
                          " Same revision {} of view {} in project {}, we just carry on with the ongoing stream",
                          fetchView.rev,
                          id,
                          project
                        )
                      ) >>
                        UIO.pure(switch)
                  }
              } yield Started(fetchView, switch)
          }

          context.pipeToSelf(io.runToFuture) {
            case Success(value) => value
            case Failure(cause) => IndexingError(cause)
          }
        }

        // Starts a new stream from the beginning
        def restartStream(
            viewIndex: ViewIndex[V]
        ): Task[StreamSwitch] =
          StreamSwitch.run(
            streamName(viewIndex.rev),
            buildStream(viewIndex, ProjectionProgress.NoProgress),
            retryStrategy,
            onFinalize,
            onFinalize
          )

        // Inits the indexing
        def init(): Behavior[IndexingViewCommand[V]] = {
          logger.debug("Init indexing for view {} in project", id, project)
          runStream(None)
          balancing()
        }

        // State after init or after reset and when view gets updated
        def balancing(): Behavior[IndexingViewCommand[V]] = Behaviors
          .receiveMessage[IndexingViewCommand[V]] {
            case Started(v, s)    =>
              buffer.unstashAll(running(v, s))
            case ViewIsDeprecated =>
              // We passivate the entity
              shard ! ClusterSharding.Passivate(self)
              passivating()
            case ViewNotFound     =>
              // We passivate the entity
              shard ! ClusterSharding.Passivate(self)
              passivating()
            case e                =>
              buffer.stash(e)
              Behaviors.same
          }

        // We just wait for the Stop message to come back from the shard
        def passivating(): Behaviors.Receive[IndexingViewCommand[V]] =
          Behaviors.receiveMessage[IndexingViewCommand[V]] {
            case Stop =>
              Behaviors.stopped
            case _    =>
              Behaviors.same
          }

        def running(viewIndex: ViewIndex[V], switch: StreamSwitch): Behavior[IndexingViewCommand[V]] =
          Behaviors
            .receiveMessage[IndexingViewCommand[V]] {
              case ViewRevision(rev) if rev > viewIndex.rev   =>
                logger.debug(
                  "'ViewRevision' event has been received and a newer revision exists for view {} in project {}",
                  id,
                  project
                )
                runStream(Some((viewIndex, switch)))
                balancing()
              case _: ViewRevision                            =>
                logger.debug(
                  "'ViewRevision' event has been received with the same revision for view {} in project {}",
                  id,
                  project
                )
                Behaviors.same
              case Restart                                    =>
                logger.debug("'Restart' event has been received for view {} in project {}", id, project)
                val restart = switch.stop >> restartStream(viewIndex)
                context.pipeToSelf(restart.runToFuture) {
                  case Success(switch) => Started(viewIndex, switch)
                  case Failure(cause)  => IndexingError(cause)
                }
                balancing()
              case _: Started[V]                              =>
                logger.warn("'Started' should not happen in 'balancing' behaviour")
                Behaviors.unhandled
              case ViewNotFound                               =>
                logger.warn("'ViewDeprecated' should not happen in 'balancing' behaviour")
                Behaviors.unhandled
              case ViewIsDeprecated                           =>
                logger.warn("'ViewDeprecated' should not happen in 'balancing' behaviour")
                Behaviors.unhandled
              case IndexingError(cause)                       =>
                throw cause
              case StreamStopped(uuid) if uuid == switch.uuid =>
                logger.debug(
                  "'StreamStopped' event with a matching uuid has been received for view {} in project {}",
                  id,
                  project
                )
                Behaviors.stopped
              case StreamStopped(_)                           =>
                logger.debug(
                  "'StreamStopped' event with a different uuid has been received for view {} in project {}",
                  id,
                  project
                )
                Behaviors.same
              case Stop                                       =>
                logger.info("'Stop' has been requested for view {} in project {}, stopping the stream", id, project)
                switch.stop.runAsyncAndForget
                Behaviors.stopped
            }
            .receiveSignal {
              case (_, PostStop)   =>
                logger.info(s"Stopped the actor {}, we stop the indexing for view {} in project {}", id, project)
                switch.stop.runAsyncAndForget
                Behaviors.same
              case (_, PreRestart) =>
                logger.info(s"Restarting the actor {}, we stop the indexing for view {} in project {}", id, project)
                switch.stop.runAsyncAndForget
                Behaviors.same
            }

        init()
      }
    }

  /**
    * Command that can be sent to the [[IndexingStreamBehaviour]]
    */
  sealed trait IndexingViewCommand[+V] extends Product with Serializable

  /**
    * Internal command when a new stream has been successfully start
    * @param viewIndex the view to index
    * @param switch the switch to interrupt the stream
    */
  private case class Started[+V](viewIndex: ViewIndex[V], switch: StreamSwitch) extends IndexingViewCommand[V]

  /**
    * Internal command when an error has been encountered handling the stream
    * @param cause the reason it failed
    */
  private case class IndexingError(cause: Throwable) extends IndexingViewCommand[Nothing]

  /**
    * Internal command when the underlying view is deprecated and that the entity should be passivated
    */
  private case object ViewIsDeprecated extends IndexingViewCommand[Nothing]

  /**
    * Internal command when the underlying view is deprecated and that the entity should be passivated
    */
  final private case object ViewNotFound extends IndexingViewCommand[Nothing]

  /**
    * Command when an update has been detected on a view and that reindexing must be restarted
    */
  final case class ViewRevision(rev: Long) extends IndexingViewCommand[Nothing]

  /**
    * A reset to start indexing from the beginning for the view
    */
  final case object Restart extends IndexingViewCommand[Nothing]

  /**
    * Command that stops the stream handled by the supervisor
    */
  final case object Stop extends IndexingViewCommand[Nothing]

  /**
    * Command returned by the stream when it stops
    * @param uuid the uuid generated by the actor responsible for the stream
    */
  private case class StreamStopped(uuid: UUID) extends IndexingViewCommand[Nothing]

}
