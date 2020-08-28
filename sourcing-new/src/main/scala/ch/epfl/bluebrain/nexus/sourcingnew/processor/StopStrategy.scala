package ch.epfl.bluebrain.nexus.sourcingnew.processor

import scala.concurrent.duration.FiniteDuration

/**
  * Strategy to stop an actor
  */
sealed trait StopStrategy {

  /**
    * @return Some(duration) if the actor should stop after no new messages are received in the ''duration'' interval;
    *         None to keep the actor alive
    */
  def lapsedSinceLastInteraction: Option[FiniteDuration]
}

object StopStrategy {

  /**
    * Stop Strategy for a transient actor
    *
    * @param lapsedSinceLastInteraction Some(duration) if the actor should stop after no new messages are received in the ''duration'' interval;
    *                                    None to keep the actor alive
    */
  final case class TransientStopStrategy(lapsedSinceLastInteraction: Option[FiniteDuration]) extends StopStrategy

  object TransientStopStrategy {
    def never: TransientStopStrategy = TransientStopStrategy(None)
  }

  /**
    * A stop strategy for persistent actors
    *
    * @param lapsedSinceLastInteraction   Some(duration) if the actor should stop after no new messages are received in the ''duration'' interval;
    *                                       None to keep the actor alive
    * @param lapsedSinceRecoveryCompleted Some(duration) if the actor should stop (and passivate);
    *                                       None to keep the actor alive (and no passivation)
    */
  final case class PersistentStopStrategy(
      lapsedSinceLastInteraction: Option[FiniteDuration],
      lapsedSinceRecoveryCompleted: Option[FiniteDuration]
  ) extends StopStrategy

  object PersistentStopStrategy {
    def never: PersistentStopStrategy = PersistentStopStrategy(None, None)
  }

}
