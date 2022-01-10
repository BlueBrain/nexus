package ch.epfl.bluebrain.nexus.delta.sourcing2

import ch.epfl.bluebrain.nexus.delta.sourcing2.Aggregate.EvaluationIO
import ch.epfl.bluebrain.nexus.delta.sourcing2.Response.{EvaluationRejection, EvaluationSuccess}
import ch.epfl.bluebrain.nexus.delta.sourcing2.model.EntityId
import monix.bio.{IO, Task, UIO}

/**
  * An aggregate based on event sourcing that can be controlled through commands;
  *
  * Successful commands result in state transitions. If we use a persistent implementation, new events are also appended
  * to the event log.
  *
  * Unsuccessful commands result in rejections returned to the caller context without any events being generated or
  * state transitions applied.
  */
trait Aggregate[State, Command, Event, Rejection] {

  /**
    * Get the current state for the entity with the given __id__
    * @param id
    *   the entity identifier
    */
  def state(id: EntityId): UIO[Option[State]]

  /**
    * Evaluates the argument __command__ in the context of entity identified by __id__.
    *
    * @param id
    *   the entity identifier
    * @param command
    *   the command to evaluate
    * @return
    *   the newly generated state and appended event if the command was evaluated successfully, or the rejection of the
    *   __command__ otherwise
    */
  def evaluate(id: EntityId, command: Command): EvaluationIO[Rejection, Event, State]

  /**
    * Tests the evaluation the argument __command__ in the context of entity identified by __id__, without applying any
    * changes to the state or event log of the entity regardless of the outcome of the command evaluation.
    *
    * @param id
    *   the entity identifier
    * @param command
    *   the command to evaluate
    * @return
    *   the state and event that would be generated in if the command was tested for evaluation successfully, or the
    *   rejection of the __command__ in otherwise
    */
  def dryRun(id: EntityId, command: Command): EvaluationIO[Rejection, Event, State]

  /**
    * Stops the entity with the given __id__
    * @param id
    *   the entity identifier
    */
  def stop(id: EntityId): Task[Unit]

}

object Aggregate {

  type EvaluationIO[Rejection, Event, State] = IO[EvaluationRejection[Rejection], EvaluationSuccess[Event, State]]

}
