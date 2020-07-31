package ch.epfl.bluebrain.nexus.sourcingnew.aggregate

import java.io.IOException

import akka.actor.typed.{ActorRef, ActorSystem}
import akka.cluster.sharding.typed.ClusterShardingSettings
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity, EntityContext, EntityTypeKey}
import akka.util.Timeout
import cats.effect.{ContextShift, Effect, IO, Timer}
import cats.syntax.all._
import retry.CatsEffect._
import retry.syntax.all._
import ch.epfl.bluebrain.nexus.sourcingnew.{Aggregate, PersistentEventDefinition, TransientEventDefinition, aggregate}
import ch.epfl.bluebrain.nexus.sourcingnew.config.AggregateConfig
import retry.{RetryDetails, RetryPolicy}

import scala.reflect.ClassTag

class AkkaAgregate[
  F[_]: Timer,
  State: ClassTag,
  EvaluateCommand: ClassTag,
  Event: ClassTag,
  Rejection: ClassTag](entityTypeKey: EntityTypeKey[Command],
                       clusterSharding: ClusterSharding,
                       askTimeout: Timeout)
                      (implicit F: Effect[F], as: ActorSystem[Nothing], policy: RetryPolicy[F])
    extends Aggregate[F, String, State, EvaluateCommand, Event, Rejection] {

  implicit private[aggregate] val contextShift: ContextShift[IO]        = IO.contextShift(as.executionContext)
  implicit private[aggregate] def noop[A]: (A, RetryDetails) => F[Unit] = retry.noop[F, A]
  implicit private val timeout: Timeout                            = askTimeout

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
  override def evaluate(id: String, command: EvaluateCommand): F[EvaluationResult] =
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
  override def dryRun(id: String, command: EvaluateCommand): F[DryRunResult] =
    send(id, { askTo: ActorRef[DryRunResult] => DryRun(id, command, askTo) })

  private def retryIf(e: Throwable): Boolean = e match {
    case _: IOException => true
    case _: EvaluationCommandTimeout[_] => true
    case _ => false
  }

  private def send[A](entityId: String, askTo: ActorRef[A] => Command): F[A] = {
    val ref = clusterSharding.entityRefFor(entityTypeKey, entityId)

    val future = IO(ref ? askTo)
    val fa     = IO.fromFuture(future).to[F]

    fa.flatMap[A] {
        case ect: EvaluationCommandTimeout[_] => F.raiseError(ect)
        case ece: EvaluationCommandError[_]   => F.raiseError(ece)
        case value                          => F.pure(value)
      }.retryingOnSomeErrors(retryIf)
  }
}

object AkkaAgregate {

  private def sharded[
    F[_]: Effect: Timer,
    State: ClassTag,
    EvaluateCommand: ClassTag,
    Event: ClassTag,
    Rejection: ClassTag](entityTypeKey: EntityTypeKey[Command],
                         eventSourceProcessor: EntityContext[Command] => EventSourceProcessor[F, State, EvaluateCommand, Event, Rejection],
                         askTimeout: Timeout,
                         shardingSettings: Option[ClusterShardingSettings])
                        (implicit as: ActorSystem[Nothing],
                         policy: RetryPolicy[F]): F[Aggregate[F, String, State, EvaluateCommand, Event, Rejection]] = {
    val F                                = implicitly[Effect[F]]
    F.delay {
      val clusterSharding = ClusterSharding(as)
      val settings = shardingSettings.getOrElse(ClusterShardingSettings(as))

      clusterSharding.init(
        Entity(entityTypeKey) {
          eventSourceProcessor(_).behavior()
        }.withSettings(settings)
      )

      new AkkaAgregate[F, State, EvaluateCommand, Event, Rejection](entityTypeKey, clusterSharding, askTimeout)
    }
  }

  def persistentSharded[
    F[_]: Effect: Timer,
    State: ClassTag,
    EvaluateCommand: ClassTag,
    Event: ClassTag,
    Rejection: ClassTag](definition: PersistentEventDefinition[F, State, EvaluateCommand, Event, Rejection],
                         config: AggregateConfig,
                         shardingSettings: Option[ClusterShardingSettings] = None)
                          (implicit as: ActorSystem[Nothing],
                           policy: RetryPolicy[F]): F[Aggregate[F, String, State, EvaluateCommand, Event, Rejection]] =
    sharded(
      EntityTypeKey[Command](definition.entityType),
      entityContext => new aggregate.EventSourceProcessor.PersistentEventProcessor[F, State, EvaluateCommand, Event, Rejection](
        entityContext.entityId,
        definition,
        config
      ),
      config.askTimeout,
      shardingSettings
    )

  def transientSharded[
    F[_]: Effect: Timer,
    State: ClassTag,
    EvaluateCommand: ClassTag,
    Event: ClassTag,
    Rejection: ClassTag](definition: TransientEventDefinition[F, State, EvaluateCommand, Event, Rejection],
                         config: AggregateConfig,
                         shardingSettings: Option[ClusterShardingSettings] = None)
                         (implicit as: ActorSystem[Nothing],
                         policy: RetryPolicy[F]): F[Aggregate[F, String, State, EvaluateCommand, Event, Rejection]] =
    sharded(
      EntityTypeKey[Command](definition.entityType),
      entityContext => new aggregate.EventSourceProcessor.TransientEventProcessor[F, State, EvaluateCommand, Event, Rejection](
        entityContext.entityId,
        definition,
        config
      ),
      config.askTimeout,
      shardingSettings
    )
}
