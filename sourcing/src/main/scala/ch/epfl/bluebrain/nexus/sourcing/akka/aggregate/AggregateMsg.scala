package ch.epfl.bluebrain.nexus.sourcing.akka.aggregate

import ch.epfl.bluebrain.nexus.sourcing.akka.Msg

/**
  * Enumeration that defines the message types exchanged with the underlying Aggregate persistent actor.
  */
sealed private[aggregate] trait AggregateMsg extends Msg

private[aggregate] object AggregateMsg {

  /**
    * Message to trigger a new event to be appended to the event log.
    *
    * @param id  the persistence id
    * @param event the event to be appended
    * @tparam Event the type of the event
    */
  final case class Append[Event](id: String, event: Event) extends AggregateMsg

  /**
    * Message to confirm that a new event has been appended to the event log.
    *
    * @param id  the persistence id
    * @param lastSeqNr the sequence number of the appended event
    */
  final case class Appended(id: String, lastSeqNr: Long) extends AggregateMsg

  /**
    * Message to retrieve the current sequence number of an event log.
    *
    * @param id the persistence id
    */
  final case class GetLastSeqNr(id: String) extends AggregateMsg

  /**
    * Message for exchanging the last known sequence number of an event log.
    *
    * @param id  the persistence id
    * @param lastSeqNr the last sequence numbers
    */
  final case class LastSeqNr(id: String, lastSeqNr: Long) extends AggregateMsg

  /**
    * Message to retrieve the current state of a stateful event log.
    *
    * @param id the persistence id
    */
  final case class GetCurrentState(id: String) extends AggregateMsg

  /**
    * Message for exchanging the current state of a stateful event log.
    *
    * @param id    the persistence id
    * @param state the current state of the event log
    * @tparam State the type of the event log state
    */
  final case class CurrentState[State](id: String, state: State) extends AggregateMsg

  /**
    * Message to evaluate a command against an aggregate.
    *
    * @param id  the persistence id
    * @param cmd the command to evaluate
    * @tparam Command the type of the command to evaluate
    */
  final case class Evaluate[Command](id: String, cmd: Command) extends AggregateMsg

  /**
    * Message for replying with the outcome of evaluating a command against an aggregate.
    *
    * @param id    the persistence id
    * @param value either a rejection or the (state, event) generated from the last command evaluation
    * @tparam Rejection the type of rejection
    * @tparam State  the type of the event log state
    * @tparam Event  the type of the event
    */
  final case class Evaluated[Rejection, State, Event](id: String, value: Either[Rejection, (State, Event)])
      extends AggregateMsg

  /**
    * Message to check a command against an aggregate.
    *
    * @param id  the persistence id
    * @param cmd the command to check
    * @tparam Command the type of the command to check
    */
  final case class Test[Command](id: String, cmd: Command) extends AggregateMsg

  /**
    * Message for replying with the outcome of checking a command against an aggregate. The command will only be tested
    * and the result will be discarded. The current state of the aggregate will not change.
    *
    * @param id    the persistence id
    * @param value either a rejection or the state that would be produced from the command evaluation
    * @tparam Rejection the type of rejection
    * @tparam Event     the type of event
    */
  final case class Tested[Rejection, State, Event](id: String, value: Either[Rejection, (State, Event)])
      extends AggregateMsg

  /**
    * Message to trigger a snapshot.
    *
    * @param id  the persistence id
    */
  final case class Snapshot(id: String) extends AggregateMsg

  /**
    * Message for replying with the successful outcome of saving a snapshot.
    *
    * @param id    the persistence id
    * @param seqNr the sequence number corresponding to the snapshot taken
    */
  final case class Snapshotted(id: String, seqNr: Long) extends AggregateMsg

  /**
    * Message for replying with a failed outcome of saving a snapshot.
    *
    * @param id the persistence id
    */
  final case class SnapshotFailed(id: String) extends AggregateMsg

}
