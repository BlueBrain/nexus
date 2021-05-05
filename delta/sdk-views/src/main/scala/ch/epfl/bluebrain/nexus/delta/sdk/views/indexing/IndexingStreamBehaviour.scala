package ch.epfl.bluebrain.nexus.delta.sdk.views.indexing
/*
scalafmt: {
  style = defaultWithAlign
  maxColumn = 200
}
 */
import akka.actor.typed._
import akka.actor.typed.scaladsl.Behaviors
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import akka.cluster.sharding.typed.scaladsl.ClusterSharding.ShardCommand
import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategy
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.views.indexing.IndexingStream.ProgressStrategy
import ch.epfl.bluebrain.nexus.delta.sdk.views.model.ViewIndex
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.stream.StreamSwitch
import com.typesafe.scalalogging.Logger
import monix.bio.{Task, UIO}
import monix.execution.Scheduler

import java.util.UUID
import scala.util.{Failure, Success}

/** The view index behavior defined in order to manage the indexing stream.
  * The created actor lifecycle is handled by the [[IndexingStreamCoordinator]]
  */
object IndexingStreamBehaviour {

  private val logger: Logger = Logger[IndexingStreamBehaviour.type]

  /** Behavior of the actor responsible for indexing a stream.
    *
    *                                      ┌──────────────┐
    *                            Started   │              │
    *                         ┌───────────►│   running    │
    *                         │            │              │
    *                         │            └──────┬───────┘
    *                         │                   │
    *                         │                   │
    * ┌────────┐        ┌─────┴──────┐            │  ViewRevision / Restart
    * │        │        │            │            │
    * │ init   ├───────►│ balancing  │◄───────────┘
    * │        │        │            │
    * └────────┘        └────┬───────┘
    *                        │             ┌──────────────┐
    *                        │             │              │
    *                        └────────────►│ passivating  │
    *                                      │              │
    *                        ViewNotFound  └──────────────┘
    *                              /
    *                      ViewIsDeprecated
    */
  def apply[V](
      shard: ActorRef[ShardCommand],
      project: ProjectRef,
      id: Iri,
      fetchView: (Iri, ProjectRef) => UIO[Option[ViewIndex[V]]],
      buildStream: IndexingStream[V],
      indexingCleanup: IndexingCleanup[V],
      retryStrategy: RetryStrategy[Throwable]
  )(implicit uuidF: UUIDF, sc: Scheduler): Behavior[IndexingViewCommand[V]] =
    Behaviors.setup[IndexingViewCommand[V]] { context =>
      // The stash is used during balancing behaviour when waiting for the stream to properly start
      Behaviors.withStash(100) { buffer =>
        import context._

        def streamName(rev: Long) = s"${project}_${id}_$rev"

        // When the stream completes or receives an error, we stop the actor
        def onFinalize(uuid: UUID) = UIO.delay(self ! StreamStopped(uuid))

        // Called during init and running when a new revision is detected
        // Stops the current stream if one exists and build a start a new one according to the new view
        def runStream(current: Option[Started[V]], progressStrategy: ProgressStrategy): Unit = {
          val io: Task[IndexingViewCommand[V]] = fetchView(id, project).flatMap {
            case None =>
              UIO.delay(logger.debug("View {} in project {} can't be found or is an aggregate one, the indexing will be passivated", id, project)) >>
                current.fold(Task.unit)(_.switch.stop).as(ViewNotFound)
            case Some(latestView) if latestView.deprecated =>
              UIO.delay(logger.info("View {} in project {} is deprecated, the indexing will be passivated", id, project)) >>
                current.fold(Task.unit)(c => c.switch.stop >> indexingCleanup(c.viewIndex)).as(ViewIsDeprecated)
            case Some(latestView) =>
              val latestSwitch = current match {
                case None                                                                   =>
                  // No stream is currently running, so we just run one with the passed progress strategy
                  UIO.delay(logger.debug("Index view {} with revision {} in project {} will start with strategy {}", id, latestView.rev, project, progressStrategy)) >>
                    startStream(latestView, progressStrategy)
                case Some(Started(currentView, switch)) if latestView.rev > currentView.rev =>
                  // A new revision of the view is detected, we stop the current stream and start a new stream
                  UIO.delay(logger.debug("New revision of view {} in project {} is detected, the indexing will be restarted from the beginning on a new index", latestView.rev, id, project)) >>
                    switch.stop >> indexingCleanup(currentView) >> startStream(latestView, progressStrategy)
                case Some(Started(_, switch))                                               =>
                  // No changes in the view detected, we continue with the current stream
                  UIO.delay(logger.debug("Same revision {} of view {} in project {}, we just carry on with the ongoing stream", latestView.rev, id, project)) >>
                    UIO.pure(switch)
              }
              latestSwitch.map(Started(latestView, _))
          }

          context.pipeToSelf(io.runToFuture) {
            case Success(value) => value
            case Failure(cause) => IndexingError(cause)
          }
        }

        // Starts a new stream from the offset defined in progressStrategy
        def startStream(view: ViewIndex[V], progressStrategy: IndexingStream.ProgressStrategy): Task[StreamSwitch] = {
          val stream = buildStream(view, progressStrategy)
          StreamSwitch.run(streamName(view.rev), stream, retryStrategy, onCancel = onFinalize, onFinalize)
        }

        // Starts the indexing, attempts to fetch the view and to start a stream
        def init(): Behavior[IndexingViewCommand[V]] = {
          logger.debug("Init indexing for view {} in project {}", id, project)
          runStream(None, ProgressStrategy.Continue)
          balancing()
        }

        // Behaviour after starting a new stream has been requested
        def balancing(): Behavior[IndexingViewCommand[V]] =
          Behaviors.receiveMessage[IndexingViewCommand[V]] {
            case Started(v, s)    =>
              // The stream has been successfully started, we stop stashing and switch to the running behaviour
              buffer.unstashAll(running(v, s))
            case ViewIsDeprecated =>
              // We passivate the entity
              shard ! ClusterSharding.Passivate(self)
              passivating()
            case ViewNotFound     =>
              // We passivate the entity
              shard ! ClusterSharding.Passivate(self)
              passivating()
            case IndexingError(cause) =>
              throw cause
            case e                    =>
              // Waiting for one of the previous messages, meanwhile we stash
              buffer.stash(e)
              Behaviors.same
          }

        // We just wait for the Stop message to come back from the shard
        def passivating(): Behaviors.Receive[IndexingViewCommand[V]] =
          Behaviors.receiveMessage[IndexingViewCommand[V]] {
            case Stop => Behaviors.stopped
            case _    => Behaviors.same
          }

        // A stream is running for the current view
        def running(viewIndex: ViewIndex[V], switch: StreamSwitch): Behavior[IndexingViewCommand[V]] =
          Behaviors
            .receiveMessage[IndexingViewCommand[V]] {
              case ViewRevision(rev) if rev > viewIndex.rev =>
                logger.debug("'ViewRevision' event has been received and a newer revision exists for view {} in project {}", id, project)
                runStream(Some(Started(viewIndex, switch)), ProgressStrategy.Continue)
                balancing()
              case _: ViewRevision =>
                logger.debug(
                  "'ViewRevision' event has been received with the same revision for view {} in project {}",
                  id,
                  project
                )
                Behaviors.same
              case Restart(progressStrategy) =>
                logger.debug("'Restart' event has been received for view {} in project {} with progress strategy {}", id, project, progressStrategy)
                val restart = switch.stop >> startStream(viewIndex, progressStrategy)
                context.pipeToSelf(restart.runToFuture) {
                  case Success(switch) => Started(viewIndex, switch)
                  case Failure(cause)  => IndexingError(cause)
                }
                balancing()
              case _: Started[V] =>
                logger.warn("'Started' should not happen in 'balancing' behaviour")
                Behaviors.unhandled
              case ViewNotFound =>
                logger.warn("'ViewDeprecated' should not happen in 'balancing' behaviour")
                Behaviors.unhandled
              case ViewIsDeprecated =>
                logger.warn("'ViewDeprecated' should not happen in 'balancing' behaviour")
                Behaviors.unhandled
              case IndexingError(cause) =>
                throw cause
              case StreamStopped(uuid) if uuid == switch.uuid =>
                logger.debug(
                  "'StreamStopped' event with a matching uuid has been received for view {} in project {}",
                  id,
                  project
                )
                Behaviors.stopped
              case StreamStopped(_) =>
                logger.debug(
                  "'StreamStopped' event with a different uuid has been received for view {} in project {}",
                  id,
                  project
                )
                Behaviors.same
              case Stop =>
                logger.info("'Stop' has been requested for view {} in project {}, stopping the stream", id, project)
                switch.stop.runAsyncAndForget
                Behaviors.stopped
            }
            .receiveSignal {
              case (_, PostStop) =>
                logger.info(s"Stopped the actor, we stop the indexing for view {} in project {}", id, project)
                switch.stop.runAsyncAndForget
                Behaviors.same
              case (_, PreRestart) =>
                logger.info(s"Restarting the actor, we stop the indexing for view {} in project {}", id, project)
                switch.stop.runAsyncAndForget
                Behaviors.same
            }

        init()
      }
    }

  /** Command that can be sent to the [[IndexingStreamBehaviour]]
    */
  sealed trait IndexingViewCommand[+V] extends Product with Serializable

  /** Internal command when a new stream has been successfully start
    * @param viewIndex the view to index
    * @param switch the switch to interrupt the stream
    */
  private case class Started[+V](viewIndex: ViewIndex[V], switch: StreamSwitch) extends IndexingViewCommand[V]

  /** Internal command when an error has been encountered handling the stream
    * @param cause the reason it failed
    */
  private case class IndexingError(cause: Throwable) extends IndexingViewCommand[Nothing]

  /** Internal command when the underlying view is deprecated and that the entity should be passivated
    */
  private case object ViewIsDeprecated extends IndexingViewCommand[Nothing]

  /** Internal command when the underlying view is deprecated and that the entity should be passivated
    */
  final private case object ViewNotFound extends IndexingViewCommand[Nothing]

  /** Command when an update has been detected on a view and that reindexing must be restarted
    */
  final case class ViewRevision(rev: Long) extends IndexingViewCommand[Nothing]

  /** Restarts the index based on the [[ProgressStrategy]]
    */
  final case class Restart(strategy: ProgressStrategy) extends IndexingViewCommand[Nothing]

  /** Command that stops the stream handled by the supervisor
    */
  final case object Stop extends IndexingViewCommand[Nothing]

  /** Command returned by the stream when it stops
    * @param uuid the uuid generated by the actor responsible for the stream
    */
  private case class StreamStopped(uuid: UUID) extends IndexingViewCommand[Nothing]

}
