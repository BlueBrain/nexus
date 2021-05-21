package ch.epfl.bluebrain.nexus.delta.sourcing.processor

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, ActorSystem, Behavior}
import akka.cluster.sharding.typed.ClusterShardingSettings
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity, EntityContext, EntityTypeKey}
import akka.util.Timeout
import ch.epfl.bluebrain.nexus.delta.kernel.{Mapper, RetryStrategy}
import ch.epfl.bluebrain.nexus.delta.kernel.syntax._
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClassUtils.simpleName
import ch.epfl.bluebrain.nexus.delta.sourcing._
import ch.epfl.bluebrain.nexus.delta.sourcing.processor.AggregateResponse._
import ch.epfl.bluebrain.nexus.delta.sourcing.processor.ProcessorCommand.AggregateRequest._
import com.typesafe.scalalogging.Logger
import monix.bio.{IO, Task, UIO}
import retry.syntax.all._

import scala.reflect.ClassTag

/**
  * Relies on cluster sharding to distribute the work for the given
  * (State, Command, Event, Rejection)
  *
  * @param entityTypeKey the key of an entity type, unique
  * @param clusterSharding the sharding for this aggregate
  * @param retryStrategy   the retry strategy to adopt
  * @param askTimeout      the ask timeout
  */
private[processor] class ShardedAggregate[State, Command, Event, Rejection](
    entityTypeKey: EntityTypeKey[ProcessorCommand],
    clusterSharding: ClusterSharding,
    retryStrategy: RetryStrategy[Throwable],
    askTimeout: Timeout
)(implicit
    State: ClassTag[State],
    Command: ClassTag[Command],
    Event: ClassTag[Event],
    Rejection: ClassTag[Rejection],
    mapper: Mapper[EvaluationError, Rejection]
) extends Aggregate[String, State, Command, Event, Rejection] {

  implicit private val logger: Logger   = Logger[ShardedAggregate.type]
  implicit private val timeout: Timeout = askTimeout
  private val component: String         = "aggregate"

  /**
    * Get the current state for the entity with the given __id__
    *
    * @param id the entity identifier
    * @return the state for the given id
    */
  override def state(id: String): UIO[State] =
    send(id, { askTo: ActorRef[StateResponse[State]] => RequestState(id, askTo) })
      .map(_.value)
      .named("getCurrentState", component, Map("entity.type" -> entityTypeKey.name))
      .logAndDiscardErrors(s"fetching the state for the id '$id' for type '${entityTypeKey.name}'")

  private def toEvaluationIO(id: String, result: Task[EvaluationResult]): EvaluationIO[Rejection, Event, State] =
    result.logAndDiscardErrors(s"processing EvaluationResult for the id '$id'").flatMap {
      case EvaluationRejection(Rejection(r))     => IO.raiseError(EvaluationRejection(r))
      case EvaluationSuccess(Event(e), State(s)) => IO.pure(EvaluationSuccess(e, s))
      case e: EvaluationError                    => IO.raiseError(EvaluationRejection(mapper.to(e)))
      case EvaluationSuccess(e, s)               =>
        val err =
          s"Unexpected Event/State type during EvaluationSuccess message: '${simpleName(
            e
          )}' provided event , expected event '${Event.simpleName}', '${simpleName(s)}' provided state , expected state '${State.simpleName}'"
        UIO.delay(logger.warn(err)) >>
          IO.terminate(new IllegalArgumentException(err))
      case EvaluationRejection(r)                =>
        val err =
          s"Unexpected Rejection type during EvaluationRejection message: '${simpleName(r)}' provided, expected '${Rejection.simpleName}'"
        UIO.delay(logger.warn(err)) >>
          IO.terminate(new IllegalArgumentException(err))
    }

  /**
    * Evaluates the argument __command__ in the context of entity identified by __id__.
    *
    * @param id      the entity identifier
    * @param command the command to evaluate
    * @return the newly generated state and appended event if the command was evaluated successfully, or the
    *         rejection of the __command__ in a task otherwise
    */
  override def evaluate(id: String, command: Command): EvaluationIO[Rejection, Event, State] =
    toEvaluationIO(
      id,
      send(id, { askTo: ActorRef[EvaluationResult] => Evaluate(id, command, askTo) })
        .named(
          "evaluate",
          component,
          Map(
            "entity.type" -> entityTypeKey.name,
            "command"     -> Command.runtimeClass.getCanonicalName
          )
        )
    )

  /**
    * Tests the evaluation the argument __command__ in the context of entity identified by __id__, without applying any
    * changes to the state or event log of the entity regardless of the outcome of the command evaluation.
    *
    * @param id      the entity identifier
    * @param command the command to evaluate
    * @return the state and event that would be generated the command was tested for evaluation
    *         successfully, or the rejection of the __command__ otherwise
    */
  override def dryRun(id: String, command: Command): EvaluationIO[Rejection, Event, State] =
    toEvaluationIO(id, send(id, { askTo: ActorRef[EvaluationResult] => DryRun(id, command, askTo) })).named(
      "dryRun",
      component,
      Map(
        "entity.type" -> entityTypeKey.name,
        "entity.id"   -> id,
        "command"     -> Command.runtimeClass.getCanonicalName
      )
    )

  private def send[A](entityId: String, askTo: ActorRef[A] => ProcessorCommand): Task[A] = {
    val ref = clusterSharding.entityRefFor(entityTypeKey, entityId)

    Task
      .deferFuture(ref ? askTo)
      .retryingOnSomeErrors(retryStrategy.retryWhen, retryStrategy.policy, retryStrategy.onError)
  }

}

object ShardedAggregate {

  private val logger: Logger = Logger[ShardedAggregate.type]

  private def sharded[State: ClassTag, Command: ClassTag, Event: ClassTag, Rejection: ClassTag](
      entityTypeKey: EntityTypeKey[ProcessorCommand],
      eventSourceProcessor: EntityContext[ProcessorCommand] => EventSourceProcessor[State, Command, Event, Rejection],
      retryStrategy: RetryStrategy[Throwable],
      askTimeout: Timeout,
      shardingSettings: Option[ClusterShardingSettings]
  )(implicit
      as: ActorSystem[Nothing],
      mapper: Mapper[EvaluationError, Rejection]
  ): UIO[Aggregate[String, State, Command, Event, Rejection]] = {
    UIO.delay {
      val clusterSharding = ClusterSharding(as)
      val settings        = shardingSettings.getOrElse(ClusterShardingSettings(as))

      clusterSharding.init(
        Entity(entityTypeKey) {
          eventSourceProcessor(_).behavior()
        }.withSettings(settings)
      )

      new ShardedAggregate[State, Command, Event, Rejection](
        entityTypeKey,
        clusterSharding,
        retryStrategy,
        askTimeout
      )
    }
  }

  /**
    * When the actor is sharded, we have to properly gracefully stopped with passivation
    * so as not to lose messages
    * @param shard the shard responsible for the actor to be passivated
    * @return the behavior to adopt when we passivate in a sharded context
    */
  private def passivateAfterInactivity(
      shard: ActorRef[ClusterSharding.ShardCommand]
  ): ActorRef[ProcessorCommand] => Behavior[ProcessorCommand] =
    (actor: ActorRef[ProcessorCommand]) => {
      shard ! ClusterSharding.Passivate(actor)
      Behaviors.same
    }

  /**
    * Creates an [[ShardedAggregate]] that makes use of persistent actors to perform its functions. The actors
    * are automatically spread across all nodes of the cluster.
    *
    * @param definition       the event definition
    * @param config           the config
    * @param shardingSettings the sharding settings
    * @param as               the actor system
    */
  def persistentSharded[State: ClassTag, Command: ClassTag, Event: ClassTag, Rejection: ClassTag](
      definition: PersistentEventDefinition[State, Command, Event, Rejection],
      config: EventSourceProcessorConfig,
      shardingSettings: Option[ClusterShardingSettings] = None
  )(implicit
      as: ActorSystem[Nothing],
      mapper: Mapper[EvaluationError, Rejection]
  ): UIO[Aggregate[String, State, Command, Event, Rejection]] = {
    sharded(
      EntityTypeKey[ProcessorCommand](definition.entityType),
      entityContext =>
        EventSourceProcessor.persistent[State, Command, Event, Rejection](
          entityContext.entityId,
          definition,
          passivateAfterInactivity(entityContext.shard),
          config
        ),
      RetryStrategy.retryOnNonFatal(config.retryStrategy, logger, "persistent sharded aggregate"),
      config.askTimeout,
      shardingSettings
    )
  }

  /**
    * Creates an [[ShardedAggregate]] that makes use of transient actors to perform its functions. The actors
    * are automatically spread across all nodes of the cluster.
    *
    * @param definition       the event definition
    * @param config           the config
    * @param shardingSettings the sharding settings
    * @param as               the actor system
    */
  def transientSharded[State: ClassTag, Command: ClassTag, Event: ClassTag, Rejection: ClassTag](
      definition: TransientEventDefinition[State, Command, Event, Rejection],
      config: EventSourceProcessorConfig,
      shardingSettings: Option[ClusterShardingSettings] = None
  )(implicit
      as: ActorSystem[Nothing],
      mapper: Mapper[EvaluationError, Rejection]
  ): UIO[Aggregate[String, State, Command, Event, Rejection]] =
    sharded(
      EntityTypeKey[ProcessorCommand](definition.entityType),
      entityContext =>
        EventSourceProcessor.transient[State, Command, Event, Rejection](
          entityContext.entityId,
          definition,
          passivateAfterInactivity(entityContext.shard),
          config
        ),
      RetryStrategy.retryOnNonFatal(config.retryStrategy, logger, "transient sharded aggregate"),
      config.askTimeout,
      shardingSettings
    )
}
