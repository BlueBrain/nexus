package ch.epfl.bluebrain.nexus.sourcing.akka

import com.github.ghik.silencer.silent

import scala.concurrent.duration._

/**
  * Defines the strategy to stop the actor.
  *
  * @tparam State   the state type
  * @tparam Command the command type
  */
trait StopStrategy[State, Command] {

  /**
    * @return Some(duration) if the actor should stop after no new messages are received in the ''duration'' interval;
    *         None to keep the actor alive
    */
  def lapsedSinceLastInteraction: Option[FiniteDuration]

  /**
    * Function that will be evaluated after an actor receives a message; its result will
    * determine if the inactivity interval should be updated or not.
    * A value of Some(0 milliseconds) wil stop the actor immediately.
    *
    * @param name        the name of the state machine
    * @param id          the id of the state machine
    * @param state       the state of the state machine
    * @param lastCommand the last evaluated command
    * @return Some(duration) if the actor should stop after no new messages are received in the ''duration'' interval;
    *         None to keep the interval as is
    */
  def lapsedAfterEvaluation(
      name: String,
      id: String,
      state: State,
      lastCommand: Option[Command]
  ): Option[FiniteDuration]
}
//noinspection ConvertibleToMethodValue
object StopStrategy {

  @silent
  @SuppressWarnings(Array("UnusedMethodParameter"))
  private[akka] def neverDuration[State, Command](
      name: String,
      id: String,
      st: State,
      cmd: Option[Command]
  ): Option[FiniteDuration] = None

  @silent
  @SuppressWarnings(Array("UnusedMethodParameter"))
  private[akka] def immediatelyDurationFn[State, Command](
      name: String,
      id: String,
      st: State,
      cmd: Option[Command]
  ): Option[FiniteDuration] = Some(0.millisecond)

  def apply[State, Command](
      sinceLast: Option[FiniteDuration],
      updateInvalidationTimer: (String, String, State, Option[Command]) => Option[FiniteDuration]
  ): StopStrategy[State, Command] =
    new StopStrategy[State, Command] {
      override val lapsedSinceLastInteraction: Option[FiniteDuration] = sinceLast
      override def lapsedAfterEvaluation(
          name: String,
          id: String,
          st: State,
          cmd: Option[Command]
      ): Option[FiniteDuration] =
        updateInvalidationTimer(name, id, st, cmd)
    }

  /**
    * An inactivity strategy that never stops the actor.
    *
    * @tparam State   the state type
    * @tparam Command the command type
    */
  def never[State, Command]: StopStrategy[State, Command] =
    apply(None, neverDuration)

  /**
    * An inactivity strategy that stops the actor after receiving one message.
    *
    * @tparam State   the state type
    * @tparam Command the command type
    */
  def immediately[State, Command]: StopStrategy[State, Command] =
    apply(None, immediatelyDurationFn)

  /**
    * An inactivity strategy that stops the actor after no new messages are received on the passed interval ''duration''.
    * When an actor receives a message, the inactivity interval can be updated by the ''updateInvalidationTimer'' function.
    *
    * @param duration                the interval duration
    * @param updateInvalidationTimer if provided, the function will be evaluated after each message exchanged; its result will
    *                                determine if the inactivity interval should be updated or not
    * @tparam State   the state type
    * @tparam Command the command type
    */
  def lapsedSinceLastInteraction[State, Command](
      duration: FiniteDuration,
      updateInvalidationTimer: (String, String, State, Option[Command]) => Option[FiniteDuration] = neverDuration _
  ): StopStrategy[State, Command] =
    apply(Some(duration), updateInvalidationTimer)

  /**
    * An inactivity strategy that stops the actor depending on the passed function.
    * When an actor receives a message, the inactivity interval can be updated by the ''updateInvalidationTimer'' function.
    *
    * @param updateInvalidationTimer if provided, the function will be evaluated after each message exchanged; its result will
    *                                determine if the inactivity interval should be updated or not
    * @tparam State   the state type
    * @tparam Command the command type
    */
  def lapsedAfterEvaluation[State, Command](
      updateInvalidationTimer: (String, String, State, Option[Command]) => Option[FiniteDuration] = neverDuration _
  ): StopStrategy[State, Command] =
    apply(None, updateInvalidationTimer)

}
