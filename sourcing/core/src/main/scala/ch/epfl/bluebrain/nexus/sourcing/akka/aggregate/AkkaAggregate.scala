package ch.epfl.bluebrain.nexus.sourcing.akka.aggregate

import java.net.URLEncoder

import akka.actor.ActorSystem
import akka.cluster.sharding.ShardRegion.{ExtractEntityId, ExtractShardId}
import akka.cluster.sharding.{ClusterSharding, ClusterShardingSettings}
import akka.persistence.query.scaladsl.CurrentEventsByPersistenceIdQuery
import akka.persistence.query.{EventEnvelope, PersistenceQuery}
import akka.routing.ConsistentHashingPool
import cats.effect.{Effect, IO, Timer}
import ch.epfl.bluebrain.nexus.sourcing.Aggregate
import ch.epfl.bluebrain.nexus.sourcing.akka.aggregate.AggregateConfig.AkkaAggregateConfig
import ch.epfl.bluebrain.nexus.sourcing.akka.aggregate.AggregateMsg._
import ch.epfl.bluebrain.nexus.sourcing.akka.{ActorRefSelection, AkkaActorIntermediator}
import retry.CatsEffect._
import retry._
import retry.syntax.all._

import scala.reflect.ClassTag

/**
  * Akka aggregate implementation that makes use of persistent actors to perform its functions. It assumes the actors
  * already exist and are externally managed.
  *
  * @param name      name of the aggregate (aggregates with the same name are part of the same group or share the
  *                  same "type")
  * @param selection an actor selection strategy for a name and an identifier
  * @param config    the sourcing configuration
  * @param as        the actor system used to run the actors
  * @tparam F         [_]       the aggregate log effect type
  * @tparam Event     the event type
  * @tparam State     the state type
  * @tparam Command   the command type
  * @tparam Rejection the rejection type
  */
class AkkaAggregate[F[_]: Timer, Event: ClassTag, State, Command, Rejection] private[aggregate] (
    override val name: String,
    selection: ActorRefSelection[F],
    config: AkkaAggregateConfig
)(implicit F: Effect[F], as: ActorSystem, policy: RetryPolicy[F])
    extends AkkaActorIntermediator(name, selection, config.askTimeout)
    with Aggregate[F, String, Event, State, Command, Rejection] {

  private val Event = implicitly[ClassTag[Event]]
  private val pq    = PersistenceQuery(as).readJournalFor[CurrentEventsByPersistenceIdQuery](config.readJournalPluginId)

  override def evaluate(id: String, command: Command): F[Either[Rejection, (State, Event)]] =
    send(id, Evaluate(id, command), (r: Evaluated[Rejection, State, Event]) => r.value)

  override def test(id: String, command: Command): F[Either[Rejection, (State, Event)]] =
    send(id, Test(id, command), (r: Tested[Rejection, State, Event]) => r.value)

  override def currentState(id: String): F[State] =
    send(id, GetCurrentState(id), (r: CurrentState[State]) => r.state)

  override def snapshot(id: String): F[Long] =
    send(id, Snapshot(id), (r: Snapshotted) => r.seqNr)

  override def lastSequenceNr(id: String): F[Long] =
    send(id, GetLastSeqNr(id), (r: LastSeqNr) => r.lastSeqNr)

  override def append(id: String, event: Event): F[Long] =
    send(id, Append(id, event), (r: Appended) => r.lastSeqNr)

  override def foldLeft[B](id: String, z: B)(f: (B, Event) => B): F[B] = {
    val future = pq
      .currentEventsByPersistenceId(s"$name-${URLEncoder.encode(id, "UTF-8")}", 0L, Long.MaxValue)
      .runFold(z) { (acc: B, el: EventEnvelope) =>
        el.event match {
          case Event(ev) => f(acc, ev)
          case _         => acc
        }
      }
    IO.fromFuture(IO(future)).to[F].retryingOnAllErrors[Throwable]
  }
}

final class AggregateTree[F[_]] {

  /**
    * Constructs an aggregate implementation that makes use of persistent actors to perform its functions. The actors
    * are automatically created within the same actor system using a consistent hashing pool of the specified size.
    *
    * @param name                the name of the aggregate
    * @param initialState        the initial state of the aggregate
    * @param next                state transition function; represented as a total function without any effect types;
    *                            state transition functions should be pure
    * @param evaluate            command evaluation function; represented as a function that returns the evaluation in
    *                            an arbitrary effect type; may be asynchronous
    * @param passivationStrategy strategy that defines how persistent actors should shutdown
    * @param config              the sourcing configuration
    * @param poolSize            the size of the consistent hashing pool of persistent actor supervisors
    * @param F                   the aggregate effect type
    * @param as                  the underlying actor system
    * @tparam Event     the aggregate event type
    * @tparam State     the aggregate state type
    * @tparam Command   the aggregate command type
    * @tparam Rejection the aggregate rejection type
    */
  @SuppressWarnings(Array("MaxParameters"))
  def apply[Event: ClassTag, State: ClassTag, Command: ClassTag, Rejection: ClassTag](
      name: String,
      initialState: State,
      next: (State, Event) => State,
      evaluate: (State, Command) => F[Either[Rejection, Event]],
      passivationStrategy: PassivationStrategy[State, Command],
      config: AkkaAggregateConfig,
      poolSize: Int
  )(
      implicit
      F: Effect[F],
      T: Timer[F],
      policy: RetryPolicy[F],
      as: ActorSystem
  ): F[Aggregate[F, String, Event, State, Command, Rejection]] =
    AkkaAggregate.treeF(name, initialState, next, evaluate, passivationStrategy, config, poolSize)
}

final class AggregateSharded[F[_]] {

  /**
    * Constructs an aggregate that makes use of persistent actors to perform its functions. The actors
    * are automatically spread across all nodes of the cluster.
    *
    * @param name                the name of the aggregate
    * @param initialState        the initial state of the aggregate
    * @param next                state transition function; represented as a total function without any effect types;
    *                            state transition functions should be pure
    * @param evaluate            command evaluation function; represented as a function that returns the evaluation in
    *                            an arbitrary effect type; may be asynchronous
    * @param passivationStrategy strategy that defines how persistent actors should shutdown
    * @param config              the sourcing configuration
    * @param shards              the number of shards to distribute across the cluster
    * @param shardingSettings    the sharding configuration
    * @param F                   the aggregate effect type
    * @param as                  the underlying actor system
    * @tparam Event     the aggregate event type
    * @tparam State     the aggregate state type
    * @tparam Command   the aggregate command type
    * @tparam Rejection the aggregate rejection type
    */
  @SuppressWarnings(Array("MaxParameters"))
  def apply[Event: ClassTag, State: ClassTag, Command: ClassTag, Rejection: ClassTag](
      name: String,
      initialState: State,
      next: (State, Event) => State,
      evaluate: (State, Command) => F[Either[Rejection, Event]],
      passivationStrategy: PassivationStrategy[State, Command],
      config: AkkaAggregateConfig,
      shards: Int,
      shardingSettings: Option[ClusterShardingSettings] = None
  )(
      implicit
      F: Effect[F],
      T: Timer[F],
      policy: RetryPolicy[F],
      as: ActorSystem
  ): F[Aggregate[F, String, Event, State, Command, Rejection]] =
    AkkaAggregate.shardedF(
      name,
      initialState,
      next,
      evaluate,
      passivationStrategy,
      config,
      shards,
      shardingSettings
    )
}

object AkkaAggregate {

  /**
    * Constructs an aggregate that makes use of persistent actors to perform its functions. The actors
    * are automatically created within the same actor system using a consistent hashing pool of the specified size.
    *
    * @see [[AggregateSharded.apply]]
    * @see [[AkkaAggregate.treeF]]
    * @tparam F[_] the aggregate log effect type
    */
  def tree[F[_]]: AggregateTree[F] =
    new AggregateTree[F]

  /**
    * Constructs an aggregate implementation that makes use of persistent actors to perform its functions. The actors
    * are automatically created within the same actor system using a consistent hashing pool of the specified size.
    *
    * @param name                the name of the aggregate
    * @param initialState        the initial state of the aggregate
    * @param next                state transition function; represented as a total function without any effect types;
    *                            state transition functions should be pure
    * @param evaluate            command evaluation function; represented as a function that returns the evaluation in
    *                            an arbitrary effect type; may be asynchronous
    * @param passivationStrategy strategy that defines how persistent actors should shutdown
    * @param config              the sourcing configuration
    * @param poolSize            the size of the consistent hashing pool of persistent actor supervisors
    * @param as                  the underlying actor system
    * @tparam F         the aggregate effect type
    * @tparam Event     the aggregate event type
    * @tparam State     the aggregate state type
    * @tparam Command   the aggregate command type
    * @tparam Rejection the aggregate rejection type
    */
  @SuppressWarnings(Array("MaxParameters"))
  def treeF[F[_]: Effect: Timer, Event: ClassTag, State: ClassTag, Command: ClassTag, Rejection: ClassTag](
      name: String,
      initialState: State,
      next: (State, Event) => State,
      evaluate: (State, Command) => F[Either[Rejection, Event]],
      passivationStrategy: PassivationStrategy[State, Command],
      config: AkkaAggregateConfig,
      poolSize: Int
  )(implicit as: ActorSystem, policy: RetryPolicy[F]): F[Aggregate[F, String, Event, State, Command, Rejection]] = {
    val F = implicitly[Effect[F]]
    F.delay {
      val props  = AggregateActor.parentProps(name, initialState, next, evaluate, passivationStrategy, config)
      val parent = as.actorOf(ConsistentHashingPool(poolSize).props(props), name)
      // route all messages through the parent pool
      val selection = ActorRefSelection.const(parent)
      new AkkaAggregate(name, selection, config)
    }
  }

  /**
    * Constructs an aggregate that makes use of persistent actors to perform its functions. The actors
    * are automatically spread across all nodes of the cluster.
    *
    * @see [[AggregateSharded.apply]]
    * @see [[AkkaAggregate.shardedF]]
    * @tparam F[_] the aggregate log effect type
    */
  def sharded[F[_]]: AggregateSharded[F] =
    new AggregateSharded[F]

  /**
    * Constructs an aggregate that makes use of persistent actors to perform its functions. The actors
    * are automatically spread across all nodes of the cluster.
    *
    * @param name                the name of the aggregate
    * @param initialState        the initial state of the aggregate
    * @param next                state transition function; represented as a total function without any effect types;
    *                            state transition functions should be pure
    * @param evaluate            command evaluation function; represented as a function that returns the evaluation in
    *                            an arbitrary effect type; may be asynchronous
    * @param passivationStrategy strategy that defines how persistent actors should shutdown
    * @param config              the sourcing configuration
    * @param shards              the number of shards to distribute across the cluster
    * @param shardingSettings    the sharding configuration
    * @param as                  the underlying actor system
    * @tparam F         the aggregate effect type
    * @tparam Event     the aggregate event type
    * @tparam State     the aggregate state type
    * @tparam Command   the aggregate command type
    * @tparam Rejection the aggregate rejection type
    */
  @SuppressWarnings(Array("MaxParameters"))
  def shardedF[F[_]: Effect: Timer, Event: ClassTag, State: ClassTag, Command: ClassTag, Rejection: ClassTag](
      name: String,
      initialState: State,
      next: (State, Event) => State,
      evaluate: (State, Command) => F[Either[Rejection, Event]],
      passivationStrategy: PassivationStrategy[State, Command],
      config: AkkaAggregateConfig,
      shards: Int,
      shardingSettings: Option[ClusterShardingSettings] = None
  )(implicit as: ActorSystem, policy: RetryPolicy[F]): F[Aggregate[F, String, Event, State, Command, Rejection]] = {
    val settings = shardingSettings.getOrElse(ClusterShardingSettings(as))
    val shardExtractor: ExtractShardId = {
      case msg: AggregateMsg => math.abs(msg.id.hashCode) % shards toString
    }
    val entityExtractor: ExtractEntityId = {
      case msg: AggregateMsg => (msg.id, msg)
    }
    val F = implicitly[Effect[F]]
    F.delay {
      val props = AggregateActor.shardedProps(name, initialState, next, evaluate, passivationStrategy, config)
      val ref   = ClusterSharding(as).start(name, props, settings, entityExtractor, shardExtractor)
      // route all messages through the sharding coordination
      val selection = ActorRefSelection.const(ref)
      new AkkaAggregate(name, selection, config)
    }
  }

}
