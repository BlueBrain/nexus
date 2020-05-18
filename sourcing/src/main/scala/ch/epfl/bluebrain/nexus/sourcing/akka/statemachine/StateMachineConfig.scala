package ch.epfl.bluebrain.nexus.sourcing.akka.statemachine

import akka.actor.ActorSystem
import akka.util.Timeout
import ch.epfl.bluebrain.nexus.sourcing.RetryStrategyConfig
import ch.epfl.bluebrain.nexus.sourcing.akka.StopStrategy
import ch.epfl.bluebrain.nexus.sourcing.akka.StopStrategy.neverDuration
import ch.epfl.bluebrain.nexus.sourcing.akka.statemachine.StateMachineConfig._

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.FiniteDuration

/**
  * State machine configuration.
  *
  * @param askTimeout                        timeout for the message exchange with the aggregate actor
  * @param commandEvaluationTimeout          timeout for evaluating commands
  * @param commandEvaluationExecutionContext the execution context where commands are to be evaluated
  * @param shards                            the number of shards for the aggregate
  * @param invalidation                      the invalidation strategy configuration
  * @param retry                             the retry strategy configuration
  */
final case class StateMachineConfig(
    askTimeout: FiniteDuration,
    commandEvaluationTimeout: FiniteDuration,
    commandEvaluationExecutionContext: String,
    shards: Int,
    invalidation: InvalidationStrategyConfig,
    retry: RetryStrategyConfig
) {

  /**
    * Computes an [[AkkaStateMachineConfig]] using an implicitly available actor system.
    *
    * @param as the underlying actor system
    */
  def akkaStateMachineConfig(implicit as: ActorSystem): AkkaStateMachineConfig =
    AkkaStateMachineConfig(
      askTimeout = Timeout(askTimeout),
      commandEvaluationMaxDuration = commandEvaluationTimeout,
      commandEvaluationExecutionContext =
        if (commandEvaluationExecutionContext == "akka") as.dispatcher
        else ExecutionContext.global,
      invalidation.influenceInvalidationOnGet
    )

  /**
    * Computes an invalidation strategy from the provided configuration and the invalidation evaluation function.
    *
    * @param updateInvalidationTimer an optional new time for when the state should be invalidated after a message exchange
    * @tparam State   the type of the state machine state
    * @tparam Command the type of the state machine command
    */
  def invalidationStrategy[State, Command](
      updateInvalidationTimer: (String, String, State, Option[Command]) => Option[FiniteDuration] =
        neverDuration[State, Command] _
  ): StopStrategy[State, Command] =
    StopStrategy[State, Command](invalidation.lapsedSinceLastInteraction, updateInvalidationTimer)
}

object StateMachineConfig {

  /**
    * Partial configuration for state machine invalidation strategy.
    *
    * @param lapsedSinceLastInteraction duration since last interaction with the state machine after which the
    *                                   state invalidation should occur
    * @param influenceInvalidationOnGet flag to decide whether or not the invalidation timer is influenced by
    *                                   the reception of a get state message
    */
  final case class InvalidationStrategyConfig(
      lapsedSinceLastInteraction: Option[FiniteDuration],
      influenceInvalidationOnGet: Boolean
  )

  /**
    * Configuration for the akka state machine implementation.
    *
    * @param askTimeout                        maximum duration to wait for a reply when communicating with an state machine actor
    * @param commandEvaluationMaxDuration      the maximum amount of time allowed for a command to evaluate before cancelled
    * @param commandEvaluationExecutionContext the execution context where command evaluation is executed
    * @param influenceInvalidationOnGet        flag to decide whether or not the invalidation timer is influenced by
    *                                          the reception of a get state message
    */
  final case class AkkaStateMachineConfig(
      askTimeout: Timeout,
      commandEvaluationMaxDuration: FiniteDuration,
      commandEvaluationExecutionContext: ExecutionContext,
      influenceInvalidationOnGet: Boolean
  )

}
