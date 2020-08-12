package ch.epfl.bluebrain.nexus.sourcingnew

/**
  * Description of an event source based entity
  * @tparam F         the effect type
  * @tparam State     the state type
  * @tparam Command   the command type
  * @tparam Event     the event type
  * @tparam Rejection the rejection type
  */
sealed trait EventDefinition[F[_], State, Command, Event, Rejection] {

  /**
    * The entity type
    * @return
    */
  def entityType: String

  /**
    * The initial state before applying any event
    * @return
    */
  def initialState: State

  /**
    * State transition function; represented as a total function without any effect types;
    * Should be pure
    *
    * @return
    */
  def next: (State, Event) => State

  /**
    * Command evaluation function; represented as a function
    * that returns the evaluation in an arbitrary effect type
    * May be asynchronous
    *
    * @return
    */
  def evaluate: (State, Command) => F[Either[Rejection, Event]]
}

/**
  * Implementation of an [[EventDefinition]]
  * relying on akka-persistence
  * @param tagger            the tags to apply to the event
  * @param snapshotStrategy  the snapshot strategy to apply
  */
final case class PersistentEventDefinition[F[_], State, Command, Event, Rejection]
  (
    entityType: String,
    initialState: State,
    next: (State, Event) => State,
    evaluate: (State, Command) => F[Either[Rejection, Event]],
    tagger: Event => Set[String],
    // TODO: Default snapshot strategy ?
    snapshotStrategy: SnapshotStrategy = SnapshotStrategy.NoSnapshot
  ) extends EventDefinition[F, State, Command, Event, Rejection]

/**
  * A transient implementation of an [[EventDefinition]]
  *
  * In case of restart or crash, the state will be lost
  */
final case class TransientEventDefinition[F[_], State, Command, Event, Rejection]
(
  entityType: String,
  initialState: State,
  next: (State, Event) => State,
  evaluate: (State, Command) => F[Either[Rejection, Event]],
) extends EventDefinition[F, State, Command, Event, Rejection]