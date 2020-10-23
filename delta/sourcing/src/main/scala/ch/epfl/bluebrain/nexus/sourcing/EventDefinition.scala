package ch.epfl.bluebrain.nexus.sourcing

import ch.epfl.bluebrain.nexus.sourcing.processor.StopStrategy
import ch.epfl.bluebrain.nexus.sourcing.processor.StopStrategy.{PersistentStopStrategy, TransientStopStrategy}
import monix.bio.IO

/**
  * Description of an event source based entity
  */
sealed trait EventDefinition[State, Command, Event, Rejection] extends Product with Serializable {

  /**
    * The entity type
    */
  def entityType: String

  /**
    * The initial state before applying any event
    */
  def initialState: State

  /**
    * State transition function; represented as a total function without any effect types;
    * Should be pure
    */
  def next: (State, Event) => State

  /**
    * Command evaluation function; represented as a function
    * that returns the evaluation in an arbitrary effect type
    * May be asynchronous
    */
  def evaluate: (State, Command) => IO[Rejection, Event]

  /**
    * Strategy to stop the actor responsible for running this definition
    */
  def stopStrategy: StopStrategy
}

/**
  * Implementation of an [[EventDefinition]]
  * relying on akka-persistence
  * @param tagger            the tags to apply to the event
  * @param snapshotStrategy  the snapshot strategy to apply
  * @param stopStrategy      the stop strategy to apply
  */
final case class PersistentEventDefinition[State, Command, Event, Rejection](
    entityType: String,
    initialState: State,
    next: (State, Event) => State,
    evaluate: (State, Command) => IO[Rejection, Event],
    tagger: Event => Set[String],
    snapshotStrategy: SnapshotStrategy = SnapshotStrategy.NoSnapshot,
    stopStrategy: PersistentStopStrategy = PersistentStopStrategy.never
) extends EventDefinition[State, Command, Event, Rejection]

/**
  * A transient implementation of an [[EventDefinition]]
  *
  * In case of restart or crash, the state will be lost
  *
  *  @param stopStrategy      the stop strategy to apply
  */
final case class TransientEventDefinition[State, Command, Event, Rejection](
    entityType: String,
    initialState: State,
    next: (State, Event) => State,
    evaluate: (State, Command) => IO[Rejection, Event],
    stopStrategy: TransientStopStrategy = TransientStopStrategy.never
) extends EventDefinition[State, Command, Event, Rejection]

object TransientEventDefinition {

  /**
    * Create a transient definition which describes a cache, where
    * evaluation results directly in a new state
    * which overwrites the former one
    *
    * @param entityType the entity type
    * @param initialState the initial state
    * @param evaluate the evaluation method
    * @param stopStrategy the stop strategy
    */
  def cache[State, Command, Rejection](
      entityType: String,
      initialState: State,
      evaluate: (State, Command) => IO[Rejection, State],
      stopStrategy: TransientStopStrategy = TransientStopStrategy.never
  ): TransientEventDefinition[State, Command, State, Rejection] =
    TransientEventDefinition(
      entityType,
      initialState,
      (_: State, newState: State) => newState,
      evaluate,
      stopStrategy
    )

}
