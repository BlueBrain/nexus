package ch.epfl.bluebrain.nexus.sourcingnew

import ch.epfl.bluebrain.nexus.sourcingnew.eventsource.{DryRunResult, EvaluationResult}

/**
  * A stateful handler based on event sourcing that can be controlled through commands;
  *
  * Successful commands result in state transitions.
  * If we use a persistent implementation, new events are also appended to the event log.
  *
  * Unsuccessful commands result in rejections returned to the caller in an __F__
  * context without any events being generated or state transitions applied.
  *
  * @tparam F
  * @tparam Id
  * @tparam State
  * @tparam Command
  * @tparam Event
  * @tparam Rejection
  */
trait EventSourceHandler[F[_], Id, State, Command, Event, Rejection] {

  /**
    * Get the current state for the entity with the given __id__
    * @param id
    * @return
    */
  def state(id: Id): F[State]

  /**
    * Given the state for the __id__ at the given __seq____
    * @param id
    * @param seq
    * @return
    */
  def state(id: Id, seq: Long): F[State]

  /**
    * Evaluates the argument __command__ in the context of entity identified by __id__.
    *
    * @param id      the entity identifier
    * @param command the command to evaluate
    * @return the newly generated state and appended event in __F__ if the command was evaluated successfully, or the
    *         rejection of the __command__ in __F__ otherwise
    */
  def evaluate(id: Id, command: Command): F[EvaluationResult]

  /**
    * Tests the evaluation the argument __command__ in the context of entity identified by __id__, without applying any
    * changes to the state or event log of the entity regardless of the outcome of the command evaluation.
    *
    * @param id      the entity identifier
    * @param command the command to evaluate
    * @return the state and event that would be generated in __F__ if the command was tested for evaluation
    *         successfully, or the rejection of the __command__ in __F__ otherwise
    */
  def dryRun(id: Id, command: Command): F[DryRunResult]

}
