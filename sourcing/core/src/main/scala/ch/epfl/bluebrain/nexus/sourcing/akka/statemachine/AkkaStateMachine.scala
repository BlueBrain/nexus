package ch.epfl.bluebrain.nexus.sourcing.akka.statemachine

import akka.actor.ActorSystem
import akka.cluster.sharding.ShardRegion.{ExtractEntityId, ExtractShardId}
import akka.cluster.sharding.{ClusterSharding, ClusterShardingSettings}
import akka.routing.ConsistentHashingPool
import cats.effect.{Effect, Timer}
import ch.epfl.bluebrain.nexus.sourcing.StateMachine
import ch.epfl.bluebrain.nexus.sourcing.akka.statemachine.StateMachineConfig.AkkaStateMachineConfig
import ch.epfl.bluebrain.nexus.sourcing.akka.statemachine.StateMachineMsg._
import ch.epfl.bluebrain.nexus.sourcing.akka.{ActorRefSelection, AkkaActorIntermediator, StopStrategy}
import retry._

import scala.reflect.ClassTag

/**
  * Akka state machine implementation that makes use of persistent actors to perform its functions. It assumes the actors
  * already exist and are externally managed.
  *
  * @param name      name of the state machine (state machines with the same name are part of the same group or share the
  *                  same "type")
  * @param selection an actor selection strategy for a name and an identifier
  * @param config    the sourcing configuration
  * @param as        the actor system used to run the actors
  * @tparam F         [_]       the state machine effect type
  * @tparam State     the state type
  * @tparam Command   the command type
  * @tparam Rejection the rejection type
  */
class AkkaStateMachine[F[_]: Timer, State, Command, Rejection] private[statemachine] (
    override val name: String,
    selection: ActorRefSelection[F],
    config: AkkaStateMachineConfig
)(implicit F: Effect[F], as: ActorSystem, policy: RetryPolicy[F])
    extends AkkaActorIntermediator(name, selection, config.askTimeout)
    with StateMachine[F, String, State, Command, Rejection] {

  override def evaluate(id: String, command: Command): F[Either[Rejection, State]] =
    send(id, Evaluate(id, command), (r: Evaluated[Rejection, State]) => r.value)

  override def test(id: String, command: Command): F[Either[Rejection, State]] =
    send(id, Test(id, command), (r: Tested[Rejection, State]) => r.value)

  override def currentState(id: String): F[State] =
    send(id, GetCurrentState(id, config.influenceInvalidationOnGet), (r: CurrentState[State]) => r.state)

}

final class StateMachineTree[F[_]] {

  /**
    * Constructs a state machine implementation that makes use of persistent actors to perform its functions. The actors
    * are automatically created within the same actor system using a consistent hashing pool of the specified size.
    *
    * @param name                 the name of the aggregate
    * @param initialState         the initial state of the aggregate
    * @param evaluate             command evaluation function; represented as a function that returns the evaluation in
    *                             an arbitrary effect type; may be asynchronous
    * @param invalidationStrategy the strategy for gracefully stopping this actor and consequently invalidating the state
    * @param config               the sourcing configuration
    * @param poolSize             the size of the consistent hashing pool of persistent actor supervisors
    * @param F                    the aggregate effect type
    * @param as                   the underlying actor system
    * @tparam State     the aggregate state type
    * @tparam Command   the aggregate command type
    * @tparam Rejection the aggregate rejection type
    */
  @SuppressWarnings(Array("MaxParameters"))
  def apply[State: ClassTag, Command: ClassTag, Rejection: ClassTag](
      name: String,
      initialState: State,
      evaluate: (State, Command) => F[Either[Rejection, State]],
      invalidationStrategy: StopStrategy[State, Command],
      config: AkkaStateMachineConfig,
      poolSize: Int
  )(
      implicit
      F: Effect[F],
      T: Timer[F],
      policy: RetryPolicy[F],
      as: ActorSystem
  ): F[StateMachine[F, String, State, Command, Rejection]] =
    AkkaStateMachine.treeF(name, initialState, evaluate, invalidationStrategy, config, poolSize)
}

final class StateMachineSharded[F[_]] {

  /**
    * Constructs a state machine that makes use of persistent actors to perform its functions. The actors
    * are automatically spread across all nodes of the cluster.
    *
    * @param name                the name of the aggregate
    * @param initialState        the initial state of the aggregate
    * @param evaluate            command evaluation function; represented as a function that returns the evaluation in
    *                            an arbitrary effect type; may be asynchronous
    * @param invalidationStrategy the strategy for gracefully stopping this actor and consequently invalidating the state
    * @param config              the sourcing configuration
    * @param shards              the number of shards to distribute across the cluster
    * @param shardingSettings    the sharding configuration
    * @param F                   the aggregate effect type
    * @param as                  the underlying actor system
    * @tparam State     the aggregate state type
    * @tparam Command   the aggregate command type
    * @tparam Rejection the aggregate rejection type
    */
  @SuppressWarnings(Array("MaxParameters"))
  def apply[State: ClassTag, Command: ClassTag, Rejection: ClassTag](
      name: String,
      initialState: State,
      evaluate: (State, Command) => F[Either[Rejection, State]],
      invalidationStrategy: StopStrategy[State, Command],
      config: AkkaStateMachineConfig,
      shards: Int,
      shardingSettings: Option[ClusterShardingSettings] = None
  )(
      implicit
      F: Effect[F],
      T: Timer[F],
      policy: RetryPolicy[F],
      as: ActorSystem
  ): F[StateMachine[F, String, State, Command, Rejection]] =
    AkkaStateMachine.shardedF(
      name,
      initialState,
      evaluate,
      invalidationStrategy,
      config,
      shards,
      shardingSettings
    )
}

object AkkaStateMachine {

  /**
    * Constructs a state machine that makes use of persistent actors to perform its functions. The actors
    * are automatically created within the same actor system using a consistent hashing pool of the specified size.
    *
    * @see [[StateMachineSharded.apply]]
    * @see [[AkkaStateMachine.treeF]]
    * @tparam F [_] the state machine effect type
    */
  def tree[F[_]]: StateMachineTree[F] =
    new StateMachineTree[F]

  /**
    * Constructs a state machine implementation that makes use of persistent actors to perform its functions. The actors
    * are automatically created within the same actor system using a consistent hashing pool of the specified size.
    *
    * @param name                 the name of the aggregate
    * @param initialState         the initial state of the aggregate
    * @param evaluate             command evaluation function; represented as a function that returns the evaluation in
    *                             an arbitrary effect type; may be asynchronous
    * @param invalidationStrategy the strategy for gracefully stopping this actor and consequently invalidating the state
    * @param config               the sourcing configuration
    * @param poolSize             the size of the consistent hashing pool of persistent actor supervisors
    * @param as                   the underlying actor system
    * @tparam F         the aggregate effect type
    * @tparam State     the aggregate state type
    * @tparam Command   the aggregate command type
    * @tparam Rejection the aggregate rejection type
    */
  @SuppressWarnings(Array("MaxParameters"))
  def treeF[F[_]: Effect: Timer, State: ClassTag, Command: ClassTag, Rejection: ClassTag](
      name: String,
      initialState: State,
      evaluate: (State, Command) => F[Either[Rejection, State]],
      invalidationStrategy: StopStrategy[State, Command],
      config: AkkaStateMachineConfig,
      poolSize: Int
  )(implicit as: ActorSystem, policy: RetryPolicy[F]): F[StateMachine[F, String, State, Command, Rejection]] = {
    val F = implicitly[Effect[F]]
    F.delay {
      val props  = StateMachineActor.parentProps(name, initialState, evaluate, invalidationStrategy, config)
      val parent = as.actorOf(ConsistentHashingPool(poolSize).props(props), name)
      // route all messages through the parent pool
      val selection = ActorRefSelection.const(parent)
      new AkkaStateMachine(name, selection, config)
    }
  }

  /**
    * Constructs a state machine that makes use of persistent actors to perform its functions. The actors
    * are automatically spread across all nodes of the cluster.
    *
    * @see [[StateMachineSharded.apply]]
    * @see [[AkkaStateMachine.shardedF]]
    * @tparam F [_] the state machine effect type
    */
  def sharded[F[_]]: StateMachineSharded[F] =
    new StateMachineSharded[F]

  /**
    * Constructs a state machine that makes use of persistent actors to perform its functions. The actors
    * are automatically spread across all nodes of the cluster.
    *
    * @param name                the name of the state machine
    * @param initialState        the initial state of the state machine
    * @param evaluate            command evaluation function; represented as a function that returns the evaluation in
    *                            an arbitrary effect type; may be asynchronous
    * @param invalidationStrategy the strategy for gracefully stopping this actor and consequently invalidating the state
    * @param config              the sourcing configuration
    * @param shards              the number of shards to distribute across the cluster
    * @param shardingSettings    the sharding configuration
    * @param as                  the underlying actor system
    * @tparam F         the state machine effect type
    * @tparam State     the state machine state type
    * @tparam Command   the state machine command type
    * @tparam Rejection the state machine rejection type
    */
  @SuppressWarnings(Array("MaxParameters"))
  def shardedF[F[_]: Effect: Timer, State: ClassTag, Command: ClassTag, Rejection: ClassTag](
      name: String,
      initialState: State,
      evaluate: (State, Command) => F[Either[Rejection, State]],
      invalidationStrategy: StopStrategy[State, Command],
      config: AkkaStateMachineConfig,
      shards: Int,
      shardingSettings: Option[ClusterShardingSettings] = None
  )(implicit as: ActorSystem, policy: RetryPolicy[F]): F[StateMachine[F, String, State, Command, Rejection]] = {
    val settings = shardingSettings.getOrElse(ClusterShardingSettings(as))
    val shardExtractor: ExtractShardId = {
      case msg: StateMachineMsg => math.abs(msg.id.hashCode) % shards toString
    }
    val entityExtractor: ExtractEntityId = {
      case msg: StateMachineMsg => (msg.id, msg)
    }
    val F = implicitly[Effect[F]]
    F.delay {
      val props = StateMachineActor.shardedProps(name, initialState, evaluate, invalidationStrategy, config)
      val ref   = ClusterSharding(as).start(name, props, settings, entityExtractor, shardExtractor)
      // route all messages through the sharding coordination
      val selection = ActorRefSelection.const(ref)
      new AkkaStateMachine(name, selection, config)
    }
  }

}
