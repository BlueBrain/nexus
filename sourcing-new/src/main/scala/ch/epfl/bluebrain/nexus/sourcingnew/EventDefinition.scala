package ch.epfl.bluebrain.nexus.sourcingnew

/**
  * Define the behavior of a family of events
  * @tparam F
  * @tparam State
  * @tparam Command
  * @tparam Event
  * @tparam Rejection
  */
sealed trait EventDefinition[F[_], State, Command, Event, Rejection] {
  def entityType: String
  def initialState: State
  def next: (State, Event) => State
  def evaluate: (State, Command) => F[Either[Rejection, Event]]
}

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


final case class TransientEventDefinition[F[_], State, Command, Event, Rejection]
(
  entityType: String,
  initialState: State,
  next: (State, Event) => State,
  evaluate: (State, Command) => F[Either[Rejection, Event]],
) extends EventDefinition[F, State, Command, Event, Rejection]