package ch.epfl.bluebrain.nexus.sourcing.processor

import ch.epfl.bluebrain.nexus.sourcing.processor.StopStrategy.{PersistentStopStrategy, TransientStopStrategy}
import pureconfig.ConfigReader
import pureconfig.generic.semiauto.deriveReader

import scala.concurrent.duration.FiniteDuration

/**
  * The configuration for a [[StopStrategy]]
  *
  * @param lapsedSinceLastInteraction   Some(duration) if the actor should stop after no new messages are received in the ''duration'' interval;
  *                                     None to keep the actor alive
  * @param lapsedSinceRecoveryCompleted Some(duration) if the actor should stop (and passivate);
  *                                     None to keep the actor alive (and no passivation)
  */
final case class StopStrategyConfig(
    lapsedSinceLastInteraction: Option[FiniteDuration],
    lapsedSinceRecoveryCompleted: Option[FiniteDuration]
) {

  /**
    * Creates a [[PersistentStopStrategy]] from the current configuration.
    */
  def persistentStrategy: PersistentStopStrategy =
    if (lapsedSinceLastInteraction.isEmpty && lapsedSinceRecoveryCompleted.isEmpty)
      PersistentStopStrategy.never
    else
      PersistentStopStrategy(lapsedSinceLastInteraction, lapsedSinceRecoveryCompleted)

  /**
    * Creates a [[TransientStopStrategy]] from the current configuration.
    */
  def transientStrategy: TransientStopStrategy =
    if (lapsedSinceLastInteraction.isEmpty)
      TransientStopStrategy.never
    else
      TransientStopStrategy(lapsedSinceLastInteraction)
}

object StopStrategyConfig {
  implicit final val stopStrategyConfigReader: ConfigReader[StopStrategyConfig] =
    deriveReader[StopStrategyConfig]
}
