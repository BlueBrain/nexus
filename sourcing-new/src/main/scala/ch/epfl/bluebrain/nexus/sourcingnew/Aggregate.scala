package ch.epfl.bluebrain.nexus.sourcingnew

import ch.epfl.bluebrain.nexus.sourcingnew.aggregate.{DryRunResult, EvaluateResult}

trait Aggregate[F[_], Id, Command, Event, Rejection, State] {

  /**
    * Get the current state for the entity with the given __id__
    * @param id
    * @return
    */
  def state(id: Id): F[Option[State]]

  /**
    * Given the state for the __id__ at the given __seq____
    * @param id
    * @param seq
    * @return
    */
  def state(id: Id, seq: Long): F[Option[State]]

  /**
    * Appends the __event____ at the end of the event log of the given __id__
    * @param id the entity identifier
    * @return the current state of the entity with id __id__
    */
  def append(id: Id, event: Event): F[State]

  /**
    * Evaluates the argument __command__ in the context of entity identified by __id__.
    *
    * @param id      the entity identifier
    * @param command the command to evaluate
    * @return the newly generated state and appended event in __F__ if the command was evaluated successfully, or the
    *         rejection of the __command__ in __F__ otherwise
    */
  def evaluate(id: Id, command: Command): F[EvaluateResult]

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
