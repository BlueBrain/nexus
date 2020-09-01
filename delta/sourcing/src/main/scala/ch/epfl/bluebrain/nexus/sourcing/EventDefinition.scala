package ch.epfl.bluebrain.nexus.sourcing

import ch.epfl.bluebrain.nexus.sourcing.processor.StopStrategy
import ch.epfl.bluebrain.nexus.sourcing.processor.StopStrategy.{PersistentStopStrategy, TransientStopStrategy}
import monix.bio.Task

import scala.reflect.ClassTag

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
  def evaluate: (State, Command) => Task[Either[Rejection, Event]]

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
    evaluate: (State, Command) => Task[Either[Rejection, Event]],
    tagger: Event => Set[String],
    // TODO: Default snapshot strategy ?
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
final case class TransientEventDefinition[State, Command, Event: ClassTag, Rejection](
    entityType: String,
    initialState: State,
    next: (State, Event) => State,
    evaluate: (State, Command) => Task[Either[Rejection, Event]],
    stopStrategy: TransientStopStrategy = TransientStopStrategy.never
) extends EventDefinition[State, Command, Event, Rejection]
