package ch.epfl.bluebrain.nexus.sourcing.processor

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, ActorSystem, Behavior}
import akka.cluster.sharding.typed.ClusterShardingSettings
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity, EntityContext, EntityTypeKey}
import akka.util.Timeout
import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategy
import ch.epfl.bluebrain.nexus.delta.kernel.syntax._
import ch.epfl.bluebrain.nexus.sourcing.Aggregate.InteractiveAggregate
import ch.epfl.bluebrain.nexus.sourcing._
import ch.epfl.bluebrain.nexus.sourcing.processor.AggregateResponse._
import ch.epfl.bluebrain.nexus.sourcing.processor.ProcessorCommand.AggregateRequest._
import monix.bio.{IO, Task, UIO}
import retry.CatsEffect._
import retry.syntax.all._

import scala.reflect.ClassTag
/*
scalafmt: {
  style = defaultWithAlign
  maxColumn = 150
}
 */
/** Relies on cluster sharding to distribute the work for the given
  * (State, Command, Event, Rejection)
  *
  * @param entityTypeKey the key of an entity type, unique
  * @param clusterSharding the sharding for this aggregate
  * @param retryStrategy   the retry strategy to adopt
  * @param askTimeout      the ask timeout
  */
private[processor] class ShardedAggregate[State, Command, Event, Rejection, Question, Answer](
    entityTypeKey: EntityTypeKey[ProcessorCommand],
    clusterSharding: ClusterSharding,
    retryStrategy: RetryStrategy[Throwable],
    askTimeout: Timeout
)(implicit State: ClassTag[State], Command: ClassTag[Command], Event: ClassTag[Event], Rejection: ClassTag[Rejection])
    extends InteractiveAggregate[String, State, Command, Event, Rejection, Question, Answer] {

  implicit private val timeout: Timeout = askTimeout
  private val component: String         = "aggregate"

  import retryStrategy._

  /** Get the current state for the entity with the given __id__
    *
    * @param id the entity identifier
    * @return the state for the given id
    */
  override def state(id: String): UIO[State] =
    send(id, { askTo: ActorRef[StateResponse[State]] => RequestState(id, askTo) })
      .map(_.value)
      .named("getCurrentState", component, Map("entity.type" -> entityTypeKey.name, "entity.id" -> id))
      .hideErrors

  private def toEvaluationIO(result: Task[EvaluationResult]): EvaluationIO[Rejection, Event, State] =
    result.hideErrors.flatMap {
      case EvaluationRejection(Rejection(r))     => IO.raiseError(EvaluationRejection(r))
      case EvaluationSuccess(Event(e), State(s)) => IO.pure(EvaluationSuccess(e, s))
      case e: EvaluationError                    =>
        // Should not append as they have been dealt with in send
        // and raised in the internal channel via hideErrors
        IO.terminate(e)
    }

  /** Evaluates the argument __command__ in the context of entity identified by __id__.
    *
    * @param id      the entity identifier
    * @param command the command to evaluate
    * @return the newly generated state and appended event if the command was evaluated successfully, or the
    *         rejection of the __command__ in a task otherwise
    */
  override def evaluate(id: String, command: Command): EvaluationIO[Rejection, Event, State] =
    toEvaluationIO(
      send(id, { askTo: ActorRef[EvaluationResult] => Evaluate(id, command, askTo) })
        .named(
          "evaluate",
          component,
          Map(
            "entity.type" -> entityTypeKey.name,
            "entity.id"   -> id,
            "command"     -> Command.runtimeClass.getCanonicalName
          )
        )
    )

  /** Tests the evaluation the argument __command__ in the context of entity identified by __id__, without applying any
    * changes to the state or event log of the entity regardless of the outcome of the command evaluation.
    *
    * @param id      the entity identifier
    * @param command the command to evaluate
    * @return the state and event that would be generated the command was tested for evaluation
    *         successfully, or the rejection of the __command__ otherwise
    */
  override def dryRun(id: String, command: Command): EvaluationIO[Rejection, Event, State] =
    toEvaluationIO(
      send(id, { askTo: ActorRef[EvaluationResult] => DryRun(id, command, askTo) })
    ).named(
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
      .flatMap {
        case e: EvaluationError => Task.raiseError[A](e)
        case value              => Task.pure(value)
      }
      .retryingOnSomeErrors(retryWhen)
  }

}

object ShardedAggregate {

  private def sharded[State: ClassTag, Command: ClassTag, Event: ClassTag, Rejection: ClassTag, Question, Answer](
      entityTypeKey: EntityTypeKey[ProcessorCommand],
      eventProcessor: EntityContext[ProcessorCommand] => EventSourceProcessor[State, Command, Event, Rejection, Question, Answer],
      retryStrategy: RetryStrategy[Throwable],
      askTimeout: Timeout,
      shardingSettings: Option[ClusterShardingSettings]
  )(implicit as: ActorSystem[Nothing]): UIO[InteractiveAggregate[String, State, Command, Event, Rejection, Question, Answer]] = {
    UIO.delay {
      val clusterSharding = ClusterSharding(as)
      val settings        = shardingSettings.getOrElse(ClusterShardingSettings(as))
      clusterSharding.init(Entity(entityTypeKey) { eventProcessor(_).behavior() }.withSettings(settings))
      new ShardedAggregate(entityTypeKey, clusterSharding, retryStrategy, askTimeout)
    }
  }

  /** When the actor is sharded, we have to properly gracefully stopped with passivation
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
   * Creates an [[ShardedAggregate]] that makes use of persistent actors to perform its functions using the provided ''definition''.
   */
  def persistentSharded[State: ClassTag, Command: ClassTag, Event: ClassTag, Rejection: ClassTag, Question, Answer](
      definition: PersistentEventDefinition[State, Command, Event, Rejection, Question, Answer],
      config: EventSourceProcessorConfig,
      retryStrategy: RetryStrategy[Throwable],
      shardingSettings: Option[ClusterShardingSettings] = None
  )(implicit as: ActorSystem[Nothing]): UIO[Aggregate[String, State, Command, Event, Rejection]] =
    sharded(
      EntityTypeKey[ProcessorCommand](definition.entityType),
      entityContext =>
        EventSourceProcessor.persistent(
          entityContext.entityId,
          definition,
          passivateAfterInactivity(entityContext.shard),
          config
        ),
      retryStrategy,
      config.askTimeout,
      shardingSettings
    )

  /**
   * Creates an [[ShardedAggregate]] that makes use of transient actors to perform its functions using the provided ''definition''.
    */
  def transientSharded[State: ClassTag, Command: ClassTag, Event: ClassTag, Rejection: ClassTag, Question, Answer](
      definition: TransientEventDefinition[State, Command, Event, Rejection, Question, Answer],
      config: EventSourceProcessorConfig,
      retryStrategy: RetryStrategy[Throwable],
      shardingSettings: Option[ClusterShardingSettings] = None
  )(implicit as: ActorSystem[Nothing]): UIO[Aggregate[String, State, Command, Event, Rejection]] =
    sharded(
      EntityTypeKey[ProcessorCommand](definition.entityType),
      entityContext =>
        EventSourceProcessor.transient(
          entityContext.entityId,
          definition,
          passivateAfterInactivity(entityContext.shard),
          config
        ),
      retryStrategy,
      config.askTimeout,
      shardingSettings
    )
}
