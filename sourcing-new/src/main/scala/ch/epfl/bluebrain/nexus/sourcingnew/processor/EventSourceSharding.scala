package ch.epfl.bluebrain.nexus.sourcingnew.processor

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, ActorSystem, Behavior}
import akka.cluster.sharding.typed.ClusterShardingSettings
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity, EntityContext, EntityTypeKey}
import akka.util.Timeout
import cats.effect.{ContextShift, Effect, IO, Timer}
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.sourcingnew._
import retry.CatsEffect._
import retry.syntax.all._

import scala.reflect.ClassTag

/**
  * Relies on cluster sharding to distribute the work for the given
  * (State, Command, Event, Rejection)
  *
  * @param entityTypeKey
  * @param clusterSharding
  * @param retryStrategy
  * @param askTimeout
  * @param timer$F$0
  * @param classTag$State$0
  * @param classTag$Command$0
  * @param classTag$Event$0
  * @param classTag$Rejection$0
  * @param F
  * @param as
  * @tparam F
  * @tparam State
  * @tparam Command
  * @tparam Event
  * @tparam Rejection
  */
class EventSourceSharding[
  F[_]: Timer,
  State: ClassTag,
  Command: ClassTag,
  Event: ClassTag,
  Rejection: ClassTag](entityTypeKey: EntityTypeKey[ProcessorCommand],
                       clusterSharding: ClusterSharding,
                       retryStrategy: RetryStrategy[F],
                       askTimeout: Timeout)
                      (implicit F: Effect[F], as: ActorSystem[Nothing])
    extends EventSourceHandler[F, String, State, Command, Event, Rejection] {

  implicit private[processor] val contextShift: ContextShift[IO]        = IO.contextShift(as.executionContext)
  implicit private val timeout: Timeout                            = askTimeout

  import retryStrategy._

  /**
    * Get the current state for the entity with the given __id__
    *
    * @param id
    * @return
    */
  override def state(id: String): F[State] =
    send(id, { askTo: ActorRef[State] => RequestState(id, askTo) })

  /**
    *
    * Given the state for the __id__ at the given __seq____
    * @param id
    * @param seq
    * @return
    */
  override def state(id: String, seq: Long): F[State] = ???

  /**
    * Evaluates the argument __command__ in the context of entity identified by __id__.
    *
    * @param id      the entity identifier
    * @param command the command to evaluate
    * @return the newly generated state and appended event in __F__ if the command was evaluated successfully, or the
    *         rejection of the __command__ in __F__ otherwise
    */
  override def evaluate(id: String, command: Command): F[EvaluationResult] =
    send(id, { askTo: ActorRef[EvaluationResult] => Evaluate(id, command, askTo) })

  /**
    * Tests the evaluation the argument __command__ in the context of entity identified by __id__, without applying any
    * changes to the state or event log of the entity regardless of the outcome of the command evaluation.
    *
    * @param id      the entity identifier
    * @param command the command to evaluate
    * @return the state and event that would be generated in __F__ if the command was tested for evaluation
    *         successfully, or the rejection of the __command__ in __F__ otherwise
    */
  override def dryRun(id: String, command: Command): F[DryRunResult] =
    send(id, { askTo: ActorRef[DryRunResult] => DryRun(id, command, askTo) })

  private def send[A](entityId: String, askTo: ActorRef[A] => ProcessorCommand): F[A] = {
    val ref = clusterSharding.entityRefFor(entityTypeKey, entityId)

    val future = IO(ref ? askTo)
    val fa     = IO.fromFuture(future).to[F]

    fa.flatMap[A] {
        case ect: EvaluationCommandTimeout[_] => F.raiseError(ect)
        case ece: EvaluationCommandError[_]   => F.raiseError(ece)
        case value                          => F.pure(value)
      }.retryingOnSomeErrors(retryWhen)
  }
}

object EventSourceSharding {

  private def sharded[
    F[_]: Effect: Timer,
    State: ClassTag,
    EvaluateCommand: ClassTag,
    Event: ClassTag,
    Rejection: ClassTag](entityTypeKey: EntityTypeKey[ProcessorCommand],
                         eventSourceProcessor: EntityContext[ProcessorCommand] => EventSourceProcessor[F, State, EvaluateCommand, Event, Rejection],
                         retryStrategy: RetryStrategy[F],
                         askTimeout: Timeout,
                         shardingSettings: Option[ClusterShardingSettings])
                        (implicit as: ActorSystem[Nothing]): F[EventSourceHandler[F, String, State, EvaluateCommand, Event, Rejection]] = {
    val F                                = implicitly[Effect[F]]
    F.delay {
      val clusterSharding = ClusterSharding(as)
      val settings = shardingSettings.getOrElse(ClusterShardingSettings(as))

      clusterSharding.init(
        Entity(entityTypeKey) {
          eventSourceProcessor(_).behavior()
        }.withSettings(settings)
      )

      new EventSourceSharding[F, State, EvaluateCommand, Event, Rejection](entityTypeKey, clusterSharding, retryStrategy, askTimeout)
    }
  }

  /**
    * When the actor is sharded, we have to properly gracefully stopped with passivation
    * so as not to lose messages
    * @param shard the shard responsible for the actor to be passivated
    * @return
    */
  private def passivateAfterInactivity(shard: ActorRef[ClusterSharding.ShardCommand]): ActorRef[ProcessorCommand] => Behavior[ProcessorCommand] =
    (actor: ActorRef[ProcessorCommand]) => {
      shard ! ClusterSharding.Passivate(actor)
      Behaviors.same
    }

  /**
    * Creates an [[EventSourceSharding]] that makes use of persistent actors to perform its functions. The actors
    * are automatically spread across all nodes of the cluster.
    *
    * @param definition
    * @param config
    * @param retryStrategy
    * @param stopStrategy
    * @param shardingSettings
    * @param as
    * @tparam F
    * @tparam State
    * @tparam EvaluateCommand
    * @tparam Event
    * @tparam Rejection
    * @return
    */
  def persistentSharded[
    F[_]: Effect: Timer,
    State: ClassTag,
    EvaluateCommand: ClassTag,
    Event: ClassTag,
    Rejection: ClassTag](definition: PersistentEventDefinition[F, State, EvaluateCommand, Event, Rejection],
                         config: EventSourceConfig,
                         retryStrategy: RetryStrategy[F],
                         stopStrategy: PersistentStopStrategy,
                         shardingSettings: Option[ClusterShardingSettings] = None)
                          (implicit as: ActorSystem[Nothing]): F[EventSourceHandler[F, String, State, EvaluateCommand, Event, Rejection]] =
    sharded(
      EntityTypeKey[ProcessorCommand](definition.entityType),
      entityContext => new processor.EventSourceProcessor.PersistentEventProcessor[F, State, EvaluateCommand, Event, Rejection](
        entityContext.entityId,
        definition,
        stopStrategy,
        passivateAfterInactivity(entityContext.shard),
        config
      ),
      retryStrategy,
      config.askTimeout,
      shardingSettings
    )

  /**
    * Creates an [[EventSourceSharding]] that makes use of transient actors to perform its functions. The actors
    * are automatically spread across all nodes of the cluster.
    * @param definition
    * @param config
    * @param retryStrategy
    * @param stopStrategy
    * @param shardingSettings
    * @param as
    * @tparam F
    * @tparam State
    * @tparam EvaluateCommand
    * @tparam Event
    * @tparam Rejection
    * @return
    */
  def transientSharded[
    F[_]: Effect: Timer,
    State: ClassTag,
    EvaluateCommand: ClassTag,
    Event: ClassTag,
    Rejection: ClassTag](definition: TransientEventDefinition[F, State, EvaluateCommand, Event, Rejection],
                         config: EventSourceConfig,
                         retryStrategy: RetryStrategy[F],
                         stopStrategy: TransientStopStrategy,
                         shardingSettings: Option[ClusterShardingSettings] = None)
                         (implicit as: ActorSystem[Nothing]): F[EventSourceHandler[F, String, State, EvaluateCommand, Event, Rejection]] =
    sharded(
      EntityTypeKey[ProcessorCommand](definition.entityType),
      entityContext => new processor.EventSourceProcessor.TransientEventProcessor[F, State, EvaluateCommand, Event, Rejection](
        entityContext.entityId,
        definition,
        stopStrategy,
        passivateAfterInactivity(entityContext.shard),
        config
      ),
      retryStrategy,
      config.askTimeout,
      shardingSettings
    )
}
