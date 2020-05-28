package ch.epfl.bluebrain.nexus.sourcing.akka.aggregate

import akka.actor.ActorSystem
import akka.util.Timeout
import ch.epfl.bluebrain.nexus.sourcing.RetryStrategyConfig
import ch.epfl.bluebrain.nexus.sourcing.akka.StopStrategy
import ch.epfl.bluebrain.nexus.sourcing.akka.StopStrategy.neverDuration
import ch.epfl.bluebrain.nexus.sourcing.akka.aggregate.AggregateConfig.{AkkaAggregateConfig, PassivationStrategyConfig}

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.FiniteDuration

/**
  * Aggregate configuration.
  *
  * @param askTimeout                        timeout for the message exchange with the aggregate actor
  * @param queryJournalPlugin                the query (read) plugin journal id
  * @param commandEvaluationTimeout          timeout for evaluating commands
  * @param commandEvaluationExecutionContext the execution context where commands are to be evaluated
  * @param shards                            the number of shards for the aggregate
  * @param passivation                       the passivation strategy configuration
  * @param retry                             the retry strategy configuration
  */
final case class AggregateConfig(
    askTimeout: FiniteDuration,
    queryJournalPlugin: String,
    commandEvaluationTimeout: FiniteDuration,
    commandEvaluationExecutionContext: String,
    shards: Int,
    passivation: PassivationStrategyConfig,
    retry: RetryStrategyConfig
) {

  /**
    * Computes an [[AkkaAggregateConfig]] using an implicitly available actor system.
    *
    * @param as the underlying actor system
    */
  def akkaAggregateConfig(implicit as: ActorSystem): AkkaAggregateConfig =
    AkkaAggregateConfig(
      askTimeout = Timeout(askTimeout),
      readJournalPluginId = queryJournalPlugin,
      commandEvaluationMaxDuration = commandEvaluationTimeout,
      commandEvaluationExecutionContext =
        if (commandEvaluationExecutionContext == "akka") as.dispatcher
        else ExecutionContext.global
    )

  /**
    * Computes a passivation strategy from the provided configuration and the passivation evaluation function.
    *
    * @param updatePassivationTimer if provided, the function will be evaluated after each message exchanged; its result will
    *                                determine if the passivation interval should be updated or not
    * @tparam State   the type of the aggregate state
    * @tparam Command the type of the aggregate command
    */
  def passivationStrategy[State, Command](
      updatePassivationTimer: (String, String, State, Option[Command]) => Option[FiniteDuration] = neverDuration _
  ): PassivationStrategy[State, Command] =
    PassivationStrategy(
      StopStrategy(passivation.lapsedSinceLastInteraction, updatePassivationTimer),
      passivation.lapsedSinceRecoveryCompleted
    )
}

object AggregateConfig {

  /**
    * Partial configuration for aggregate passivation strategy.
    *
    * @param lapsedSinceLastInteraction   duration since last interaction with the aggregate after which the passivation
    *                                     should occur
    * @param lapsedSinceRecoveryCompleted duration since the aggregate recovered after which the passivation should
    *                                     occur
    */
  final case class PassivationStrategyConfig(
      lapsedSinceLastInteraction: Option[FiniteDuration],
      lapsedSinceRecoveryCompleted: Option[FiniteDuration]
  )

  /**
    * Configuration for the akka aggregate implementation.
    *
    * @param askTimeout                        maximum duration to wait for a reply when communicating with an aggregate actor
    * @param readJournalPluginId               the id of the read journal for querying across entity event logs
    * @param commandEvaluationMaxDuration      the maximum amount of time allowed for a command to evaluate before cancelled
    * @param commandEvaluationExecutionContext the execution context where command evaluation is executed
    */
  final case class AkkaAggregateConfig(
      askTimeout: Timeout,
      readJournalPluginId: String,
      commandEvaluationMaxDuration: FiniteDuration,
      commandEvaluationExecutionContext: ExecutionContext
  )

}
