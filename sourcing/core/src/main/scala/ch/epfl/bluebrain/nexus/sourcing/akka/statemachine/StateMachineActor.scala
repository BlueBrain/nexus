package ch.epfl.bluebrain.nexus.sourcing.akka.statemachine

import java.net.URLDecoder
import java.util.concurrent.TimeoutException

import akka.actor.{Actor, ActorLogging, ActorRef, Props, ReceiveTimeout, Stash}
import cats.effect.syntax.all._
import cats.effect.{ContextShift, Effect, IO, Timer}
import cats.implicits._
import ch.epfl.bluebrain.nexus.sourcing.akka.Msg._
import ch.epfl.bluebrain.nexus.sourcing.akka.StopStrategy
import ch.epfl.bluebrain.nexus.sourcing.akka.statemachine.StateMachineConfig.AkkaStateMachineConfig
import ch.epfl.bluebrain.nexus.sourcing.akka.statemachine.StateMachineMsg._

import scala.collection.mutable
import scala.concurrent.duration.FiniteDuration
import scala.reflect.ClassTag
import scala.util.control.NonFatal

/**
  * An actor implementation that handles the role of a StateMachine: manages state transitions and the command evaluation for a single entity.
  *
  * @param name                 name of the state machine (state machines with the same name are part of the same group or share
  *                             the same "type")
  * @param initialState         the initial state of the state machine
  * @param evaluate             command evaluation function; represented as a function that returns the evaluation in an
  *                             arbitrary effect type; may be asynchronous
  * @param invalidationStrategy the strategy for gracefully stopping this actor and consequently invalidating the state
  * @param config               akka sourcing configuration
  * @tparam F         the evaluation effect type
  * @tparam State     the state type of the aggregate
  * @tparam Command   the command type of the aggregate
  * @tparam Rejection the command evaluation rejection type of the aggregate
  */
//noinspection ActorMutableStateInspection
abstract private[statemachine] class StateMachineActor[
    F[_]: Effect,
    State: ClassTag,
    Command: ClassTag,
    Rejection: ClassTag
](
    name: String,
    initialState: State,
    evaluate: (State, Command) => F[Either[Rejection, State]],
    invalidationStrategy: StopStrategy[State, Command],
    config: AkkaStateMachineConfig
) extends Actor
    with Stash
    with ActorLogging {

  private val State     = implicitly[ClassTag[State]]
  private val Command   = implicitly[ClassTag[Command]]
  private val Rejection = implicitly[ClassTag[Rejection]]

  private var state = initialState
  //noinspection ActorMutableStateInspection
  private var stopRequested: Boolean = false

  implicit private val timer: Timer[IO]     = IO.timer(config.commandEvaluationExecutionContext)
  implicit private val cs: ContextShift[IO] = IO.contextShift(config.commandEvaluationExecutionContext)

  def id: String

  def targetedActorRef(): ActorRef

  override def preStart(): Unit = {
    super.preStart()
    log.debug("StateMachineActor with id '{}' started", id)
    stopAfterInactivity(invalidationStrategy.lapsedSinceLastInteraction)
  }

  override def receive: Receive = {
    case GetCurrentState(mid) if mid == id =>
      updateStopAfterInactivity()
      sender() ! CurrentState(id, state)
      log.debug("Replied with CurrentState '{}' from actor '{}'", state, id)
    case Evaluate(mid, value) if mid == id =>
      value match {
        case Command(cmd) =>
          log.debug("Evaluating command '{}' on actor '{}'", cmd, id)
          evaluateCommand(cmd)
          context.become(evaluating(cmd, sender()))
        // $COVERAGE-OFF$
        case _ =>
          log.error(
            "Received a command '{}' incompatible with the expected type '{}'",
            value,
            Command.runtimeClass.getSimpleName
          )
          updateStopAfterInactivity()
          sender() ! TypeError(id, Command.runtimeClass.getSimpleName, value)
        // $COVERAGE-ON$
      }

    case Test(mid, value) if mid == id =>
      value match {
        case Command(cmd) =>
          log.debug("Testing command '{}' on actor '{}'", cmd, id)
          evaluateCommand(cmd, test = true)
          context.become(testing(cmd, sender()))
        // $COVERAGE-OFF$
        case _ =>
          log.error(
            "Received a command '{}' incompatible with the expected type '{}'",
            value,
            Command.runtimeClass.getSimpleName
          )
          updateStopAfterInactivity()
          sender() ! TypeError(id, Command.runtimeClass.getSimpleName, value)
        // $COVERAGE-ON$
      }

    // $COVERAGE-OFF$
    case msg: StateMachineMsg if msg.id != id =>
      log.warning("Unexpected message id '{}' received in actor with id '{}'", msg.id, id)
      updateStopAfterInactivity()
      sender() ! UnexpectedMsgId(id, msg.id)
    // $COVERAGE-ON$
  }

  private def evaluating(cmd: Command, previous: ActorRef): Receive = {
    case GetCurrentState(mid) if mid == id =>
      updateStopAfterInactivity()
      sender() ! CurrentState(id, state)
      log.debug("Replied with CurrentState '{}' from actor '{}'", state, id)
    case Evaluated(_, Left(Rejection(rejection))) =>
      log.debug("Rejected command '{}' on actor '{}' because '{}'", cmd, id, rejection)
      updateStopAfterInactivity(Some(cmd))
      previous ! Evaluated[Rejection, State](id, Left(rejection))
      context.become(receive)
      unstashAll()
    case Evaluated(_, Right(State(newState))) =>
      state = newState
      log.debug("Applied state '{}' to actor '{}'", state, id)
      updateStopAfterInactivity(Some(cmd))
      previous ! Evaluated[Rejection, State](id, Right(state))
      context.become(receive)
      unstashAll()
    case cet: CommandEvaluationTimeout[_] =>
      log.debug("Returning the command evaluation timeout on actor '{}' to the sender", id)
      updateStopAfterInactivity(Some(cmd))
      previous ! cet
      context.become(receive)
      unstashAll()
    case cee: CommandEvaluationError[_] =>
      log.debug("Returning the command evaluation error on actor '{}' to the sender", id)
      updateStopAfterInactivity(Some(cmd))
      previous ! cee
      context.become(receive)
      unstashAll()
    // $COVERAGE-OFF$
    case msg: StateMachineMsg if msg.id != id =>
      log.warning("Unexpected message id '{}' received in actor with id '{}'", msg.id, id)
      updateStopAfterInactivity()
      sender() ! UnexpectedMsgId(id, msg.id)
    // $COVERAGE-ON$
    case other =>
      log.debug("New message '{}' received for '{}' while evaluating a command, stashing", other, id)
      stash()
  }

  private def testing(cmd: Command, previous: ActorRef): Receive = {
    case GetCurrentState(mid) if mid == id =>
      sender() ! CurrentState(id, state)
      log.debug("Replied with CurrentState '{}' from actor '{}'", state, id)
      updateStopAfterInactivity()
    case Evaluated(_, Left(Rejection(rejection))) =>
      previous ! Tested[Rejection, State](id, Left(rejection))
      log.debug("Rejected test command '{}' on actor '{}' because '{}'", cmd, id, rejection)
      context.become(receive)
      unstashAll()
      updateStopAfterInactivity()
    case Evaluated(_, Right(State(newState))) =>
      previous ! Tested[Rejection, State](id, Right(newState))
      log.debug("Accepted test command '{}' on actor '{}' producing '{}'", cmd, id, newState)
      context.become(receive)
      unstashAll()
      updateStopAfterInactivity()
    case cet: CommandEvaluationTimeout[_] =>
      log.debug("Returning the command testing timeout on actor '{}' to the sender", id)
      updateStopAfterInactivity(Some(cmd))
      previous ! cet
      context.become(receive)
      unstashAll()
    case cee: CommandEvaluationError[_] =>
      log.debug("Returning the command testing error on actor '{}' to the sender", id)
      updateStopAfterInactivity(Some(cmd))
      previous ! cee
      context.become(receive)
      unstashAll()
    case msg: StateMachineMsg if msg.id != id =>
      // $COVERAGE-OFF$
      log.warning("Unexpected message id '{}' received in actor with id '{}'", msg.id, id)
      updateStopAfterInactivity()
      sender() ! UnexpectedMsgId(id, msg.id)
    // $COVERAGE-ON$
    case other =>
      log.debug("New message '{}' received for '{}' while testing a command, stashing", other, id)
      stash()
  }

  // $COVERAGE-OFF$
  override def unhandled(message: Any): Unit = message match {
    case ReceiveTimeout => context.stop(self)
    case Done(_)        => context.stop(self)
    case other =>
      log.error("Received unknown message '{}' for actor with id '{}'", other, id)
      super.unhandled(other)
      updateStopAfterInactivity()
  }
  // $COVERAGE-ON$

  private def evaluateCommand(cmd: Command, test: Boolean = false): Unit = {
    val scope = if (test) "testing" else "evaluating"
    val eval = for {
      _           <- IO.shift(config.commandEvaluationExecutionContext)
      eitherState <- evaluate(state, cmd).toIO.timeout(config.commandEvaluationMaxDuration)
      _           <- IO.shift(context.dispatcher)
      _           <- IO(self ! Evaluated(id, eitherState))
    } yield ()
    val io = eval.onError {
      case th: TimeoutException =>
        log.error(th, s"Timed out while $scope command '{}' on actor '{}'", cmd, id)
        IO.shift(context.dispatcher) >> IO(self ! CommandEvaluationTimeout(id, cmd))
      case NonFatal(th) =>
        log.error(th, s"Error while $scope command '{}' on actor '{}'", cmd, id)
        IO.shift(context.dispatcher) >> IO(self ! CommandEvaluationError(id, cmd, Option(th.getMessage)))
    }
    io.unsafeRunAsyncAndForget()
  }

  private def updateStopAfterInactivity(cmd: Option[Command] = None): Unit =
    stopAfterInactivity(invalidationStrategy.lapsedAfterEvaluation(name, id, state, cmd))

  private def stopAfterInactivity(intervalOpt: Option[FiniteDuration]): Unit =
    intervalOpt.foreach { interval =>
      if (interval.toMillis < 1L) {
        if (!stopRequested) {
          targetedActorRef() ! Done(id)
          log.debug("Stop actor with name '{}' and id '{}' immediately", name, id)
          stopRequested = true
        }
      } else {
        context.setReceiveTimeout(interval)
        log.debug("Scheduled stop for actor with name '{}' and id '{}' after inactivity '{}'", name, id, interval)
      }
    }

}

final private[statemachine] case class Terminated(id: String, ref: ActorRef)

//noinspection ActorMutableStateInspection
private[statemachine] class ParentStateMachineActor(name: String, childProps: String => Props)
    extends Actor
    with ActorLogging {

  private val buffer = mutable.Map.empty[String, Vector[(ActorRef, StateMachineMsg)]]

  def receive: Receive = {
    case Done(id) if !buffer.contains(id) =>
      val child = sender()
      context.watchWith(child, Terminated(id, child))
      buffer.put(id, Vector.empty)
      child ! Done(id)
    case msg: StateMachineMsg if buffer.contains(msg.id) =>
      // must buffer messages
      val messages = buffer(msg.id) :+ (sender() -> msg)
      val _        = buffer.put(msg.id, messages)
    case msg: StateMachineMsg =>
      val childName = s"$name-${msg.id}"
      log.debug("Routing message '{}' to child '{}'", msg, childName)
      child(msg.id).forward(msg)
    case Terminated(id, _) if buffer.contains(id) =>
      val messages = buffer(id)
      if (messages.nonEmpty) {
        val newChild = child(id)
        messages.foreach {
          case (s, msg) => newChild.!(msg)(s)
        }
      }
      val _ = buffer.remove(id)
  }

  private def child(id: String): ActorRef = {
    val childName = s"$name-$id"
    context
      .child(childName)
      .getOrElse(context.actorOf(childProps(id), childName))
  }
}

private[statemachine] class ChildStateMachineActor[
    F[_]: Effect,
    State: ClassTag,
    Command: ClassTag,
    Rejection: ClassTag
](
    override val id: String,
    name: String,
    initialState: State,
    evaluate: (State, Command) => F[Either[Rejection, State]],
    invalidationStrategy: StopStrategy[State, Command],
    config: AkkaStateMachineConfig
) extends StateMachineActor[F, State, Command, Rejection](name, initialState, evaluate, invalidationStrategy, config) {

  override def targetedActorRef(): ActorRef = context.parent

}

private[statemachine] class ShardedStateMachineActor[
    F[_]: Effect,
    State: ClassTag,
    Command: ClassTag,
    Rejection: ClassTag
](
    name: String,
    initialState: State,
    evaluate: (State, Command) => F[Either[Rejection, State]],
    invalidationStrategy: StopStrategy[State, Command],
    config: AkkaStateMachineConfig
) extends StateMachineActor[F, State, Command, Rejection](name, initialState, evaluate, invalidationStrategy, config) {

  override def id: String = URLDecoder.decode(self.path.name, "UTF-8")

  override def targetedActorRef(): ActorRef = self
}

object StateMachineActor {

  @SuppressWarnings(Array("MaxParameters"))
  private[statemachine] def parentProps[
      F[_]: Effect,
      State: ClassTag,
      Command: ClassTag,
      Rejection: ClassTag
  ](
      name: String,
      initialState: State,
      evaluate: (State, Command) => F[Either[Rejection, State]],
      invalidationStrategy: StopStrategy[State, Command],
      config: AkkaStateMachineConfig
  ): Props =
    Props(new ParentStateMachineActor(name, childProps(name, initialState, evaluate, invalidationStrategy, config)))

  @SuppressWarnings(Array("MaxParameters"))
  private[statemachine] def childProps[
      F[_]: Effect,
      State: ClassTag,
      Command: ClassTag,
      Rejection: ClassTag
  ](
      name: String,
      initialState: State,
      evaluate: (State, Command) => F[Either[Rejection, State]],
      invalidationStrategy: StopStrategy[State, Command],
      config: AkkaStateMachineConfig
  )(id: String): Props =
    Props(new ChildStateMachineActor(id, name, initialState, evaluate, invalidationStrategy, config))

  @SuppressWarnings(Array("MaxParameters"))
  private[statemachine] def shardedProps[
      F[_]: Effect,
      State: ClassTag,
      Command: ClassTag,
      Rejection: ClassTag
  ](
      name: String,
      initialState: State,
      evaluate: (State, Command) => F[Either[Rejection, State]],
      invalidationStrategy: StopStrategy[State, Command],
      config: AkkaStateMachineConfig
  ): Props =
    Props(new ShardedStateMachineActor(name, initialState, evaluate, invalidationStrategy, config))
}
