package ch.epfl.bluebrain.nexus.sourcing.akka.aggregate

import ch.epfl.bluebrain.nexus.sourcing.akka.StopStrategy
import ch.epfl.bluebrain.nexus.sourcing.akka.StopStrategy._

import scala.concurrent.duration.FiniteDuration

/**
  * A passivation strategy for aggregates backed by persistent actors.
  *
  * @tparam State   the state type
  * @tparam Command the command type
  */
trait PassivationStrategy[State, Command] extends StopStrategy[State, Command] {

  /**
    * @return Some(duration) if the actor should passivate after the provided duration, None for no passivation
    */
  /**
    * @return Some(duration) if the actor should stop (and passivate);
    *         None to keep the actor alive (and no passivation).
    */
  def lapsedSinceRecoveryCompleted: Option[FiniteDuration]
}

//noinspection ConvertibleToMethodValue
object PassivationStrategy {

  def apply[State, Command](
      inactivityStopStrategy: StopStrategy[State, Command],
      sinceRecovered: Option[FiniteDuration]
  ): PassivationStrategy[State, Command] =
    new PassivationStrategy[State, Command] {
      override def lapsedSinceRecoveryCompleted: Option[FiniteDuration] = sinceRecovered
      override def lapsedSinceLastInteraction: Option[FiniteDuration] =
        inactivityStopStrategy.lapsedSinceLastInteraction
      override def lapsedAfterEvaluation(
          name: String,
          id: String,
          st: State,
          cmd: Option[Command]
      ): Option[FiniteDuration] =
        inactivityStopStrategy.lapsedAfterEvaluation(name, id, st, cmd)
    }

  /**
    * A passivation strategy that never executes.
    *
    * @tparam State   the state type
    * @tparam Command the command type
    */
  def never[State, Command]: PassivationStrategy[State, Command] =
    apply(StopStrategy.never, None)

  /**
    * A passivation strategy that executes after each processed message.
    *
    * @tparam State   the state type
    * @tparam Command the command type
    */
  def immediately[State, Command]: PassivationStrategy[State, Command] =
    apply(StopStrategy.immediately, None)

  /**
    * A passivation strategy that executes based on the interval lapsed since the last interaction with the aggregate
    * actor.
    *
    * @param duration                the interval duration
    * @param updatePassivationTimer if provided, the function will be evaluated after each message exchanged; its result will
    *                                determine if the passivation interval should be updated or not
    * @tparam State   the state type
    * @tparam Command the command type
    * @return
    */
  def lapsedSinceLastInteraction[State, Command](
      duration: FiniteDuration,
      updatePassivationTimer: (String, String, State, Option[Command]) => Option[FiniteDuration] = neverDuration _
  ): PassivationStrategy[State, Command] =
    apply(StopStrategy.lapsedSinceLastInteraction(duration, updatePassivationTimer), None)

  /**
    * A passivation strategy that executes based on the interval lapsed since the aggregate actor has recovered its
    * state (which happens immediately after the actor is started).
    *
    * @param duration        the interval duration
    * @param updatePassivationTimer if provided, the function will be evaluated after each message exchanged; its result will
    *                                determine if the passivation interval should be updated or not
    * @tparam State          the state type
    * @tparam Command        the command type
    * @return
    */
  def lapsedSinceRecoveryCompleted[State, Command](
      duration: FiniteDuration,
      updatePassivationTimer: (String, String, State, Option[Command]) => Option[FiniteDuration] = neverDuration _
  ): PassivationStrategy[State, Command] =
    apply(StopStrategy(None, updatePassivationTimer), Some(duration))
}
