package ch.epfl.bluebrain.nexus.sourcing.projections

import akka.Done
import akka.actor.{Actor, ActorLogging, ActorRef, ActorSystem, Props, Status}
import akka.cluster.singleton.{ClusterSingletonManager, ClusterSingletonManagerSettings}
import akka.pattern.{ask, pipe}
import akka.stream.scaladsl.{Keep, RunnableGraph, Sink, Source}
import akka.stream.{KillSwitches, UniqueKillSwitch}
import akka.util.Timeout
import cats.effect.syntax.all._
import cats.effect.{ContextShift, Effect, IO}
import cats.implicits._
import ch.epfl.bluebrain.nexus.sourcing.projections.StreamSupervisor.{FetchLatestState, LatestState, Stop}

import scala.concurrent.{ExecutionContext, Future}
import scala.reflect.ClassTag

/**
  * Component that supervises a stream through an actor.
  *
  * @param actor the underlying actor
  */
class StreamSupervisor[F[_], A](val actor: ActorRef)(implicit F: Effect[F], as: ActorSystem, askTimeout: Timeout) {
  implicit private val ec: ExecutionContext           = as.dispatcher
  implicit private val contextShift: ContextShift[IO] = IO.contextShift(ec)

  /**
    * Fetches the latest state from the underlying actor
    * [[ch.epfl.bluebrain.nexus.sourcing.projections.StreamSupervisor.StreamSupervisorActor]] .
    *
    * @return latest state wrapped in [[F]]
    */
  def state()(implicit A: ClassTag[Option[A]]): F[Option[A]] = IO.fromFuture(IO(actor ? FetchLatestState)).to[F].map {
    case LatestState(A(state)) => state
  }

  /**
    * Stops the stream.
    */
  def stop(): F[Unit] = F.pure(actor ! Stop)

}

object StreamSupervisor {
  final private[sourcing] case class Start(any: Any)
  final private[sourcing] case object Stop
  final private[sourcing] case object FetchLatestState
  final private[sourcing] case class LatestState[A](state: Option[A])

  /**
    * Actor implementation that builds and manages a stream ([[RunnableGraph]]).
    *
    * @param sourceF a source wrapped on the effect type F[_]
    */
  class StreamSupervisorActor[F[_], A](sourceF: F[Source[A, _]])(implicit F: Effect[F])
      extends Actor
      with ActorLogging {

    implicit private val as: ActorSystem      = context.system
    implicit private val ec: ExecutionContext = as.dispatcher
    //noinspection ActorMutableStateInspection
    private var state: Option[A] = None

    override def preStart(): Unit = {
      super.preStart()
      initialize()
    }

    def initialize(): Unit =
      buildStream()
        .map { stream =>
          val (killSwitch, doneFuture) = stream.run()
          self ! killSwitch
          doneFuture.pipeTo(self)
          ()
        }
        .toIO
        .unsafeRunAsyncAndForget()

    private def buildStream(): F[RunnableGraph[(UniqueKillSwitch, Future[Done])]] =
      sourceF.map { source =>
        source
          .viaMat(KillSwitches.single)(Keep.right)
          .map { latest =>
            state = Some(latest)
            latest
          }
          .toMat(Sink.ignore)(Keep.both)
      }

    override def receive: Receive = {
      // $COVERAGE-OFF$
      case killSwitch: UniqueKillSwitch =>
        context.become(running(killSwitch))
      case Stop =>
        log.debug("Received stop signal while waiting for a start value, stopping")
        context.stop(self)
      // $COVERAGE-ON$

      case FetchLatestState => sender() ! LatestState(state)
    }

    private def running(killSwitch: UniqueKillSwitch): Receive = {
      case Done =>
        log.error("Stream finished unexpectedly, restarting")
        killSwitch.shutdown()
        initialize()
        context.become(receive)
      // $COVERAGE-OFF$
      case Status.Failure(th) =>
        log.error(th, "Stream finished unexpectedly with an error")
        killSwitch.shutdown()
        initialize()
        context.become(receive)
      // $COVERAGE-ON$
      case Stop =>
        log.debug("Received stop signal, stopping stream")
        killSwitch.shutdown()
        context.become(stopping)
      case FetchLatestState => sender() ! LatestState(state)
    }

    private def stopping: Receive = {
      case Done =>
        log.debug("Stream finished, stopping")
        context.stop(self)
      // $COVERAGE-OFF$
      case Status.Failure(th) =>
        log.error("Stream finished with an error", th)
        context.stop(self)
      // $COVERAGE-ON$
      case FetchLatestState => sender() ! LatestState(state)
    }
  }

  /**
    * Builds a [[Props]] for a [[StreamSupervisorActor]] with its configuration.
    *
    * @param sourceF a stream wrapped in an effect type
    */
  // $COVERAGE-OFF$
  private def props[F[_]: Effect, A](sourceF: F[Source[A, _]]): Props =
    Props(new StreamSupervisorActor(sourceF))

  private def singletonProps[F[_]: Effect, A](sourceF: F[Source[A, _]])(
      implicit as: ActorSystem
  ): Props =
    ClusterSingletonManager.props(
      Props(new StreamSupervisorActor(sourceF)),
      terminationMessage = Stop,
      settings = ClusterSingletonManagerSettings(as)
    )

  /**
    * Builds a [[StreamSupervisor]].
    *
    * @param sourceF a stream wrapped in an effect type
    * @param name    the actor name
    */
  final def start[F[_]: Effect, A](
      sourceF: F[Source[A, _]],
      name: String
  )(
      implicit as: ActorSystem,
      askTimeout: Timeout
  ): StreamSupervisor[F, A] =
    start(sourceF, name, as.actorOf)

  /**
    * Builds a [[StreamSupervisor]].
    *
    * @param sourceF a stream wrapped in an effect type
    * @param name    the actor name
    * @param actorOf a function that given an actor Props and a name it instantiates an ActorRef
    */
  final def start[F[_]: Effect, A](
      sourceF: F[Source[A, _]],
      name: String,
      actorOf: (Props, String) => ActorRef
  )(
      implicit as: ActorSystem,
      askTimeout: Timeout
  ): StreamSupervisor[F, A] =
    new StreamSupervisor[F, A](actorOf(props(sourceF), name))

  /**
    * Builds a [[StreamSupervisor]] based on a cluster singleton actor.
    *
    * @param sourceF a stream wrapped in an effect type
    * @param name    the actor name
    */
  final def startSingleton[F[_]: Effect, A](
      sourceF: F[Source[A, _]],
      name: String
  )(
      implicit as: ActorSystem,
      askTimeout: Timeout
  ): StreamSupervisor[F, A] =
    start(sourceF, name, as.actorOf)

  /**
    * Builds a [[StreamSupervisor]] based on a cluster singleton actor.
    *
    * @param sourceF a stream wrapped in an effect type
    * @param name    the actor name
    * @param actorOf a function that given an actor Props and a name it instantiates an ActorRef
    */
  final def startSingleton[F[_]: Effect, A](
      sourceF: F[Source[A, _]],
      name: String,
      actorOf: (Props, String) => ActorRef
  )(
      implicit as: ActorSystem,
      askTimeout: Timeout
  ): StreamSupervisor[F, A] =
    new StreamSupervisor[F, A](actorOf(singletonProps(sourceF), name))
}
