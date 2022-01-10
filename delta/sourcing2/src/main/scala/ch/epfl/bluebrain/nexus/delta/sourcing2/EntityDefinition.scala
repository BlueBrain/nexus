package ch.epfl.bluebrain.nexus.delta.sourcing2

import ch.epfl.bluebrain.nexus.delta.sourcing2.decoder.PayloadDecoder
import ch.epfl.bluebrain.nexus.delta.sourcing2.event.EventSerializer
import ch.epfl.bluebrain.nexus.delta.sourcing2.model.EntityType
import ch.epfl.bluebrain.nexus.delta.sourcing2.state.StateSerializer

/**
  * Description of an event source based entity
  */
sealed trait EntityDefinition[State, Command, Event, Rejection] extends Product with Serializable {

  /**
    * The entity type
    */
  def entityType: EntityType

  def eventProcessor: EventProcessor[Command, Event, State, Rejection]

}

object EntityDefinition {

  /**
    * Implementation of an [[EntityDefinition]] which persists the result
    */
  final case class PersistentDefinition[State, Command, Event, Rejection](
      entityType: EntityType,
      eventProcessor: EventProcessor[Command, Event, State, Rejection],
      eventSerializer: EventSerializer[Event],
      eventDecoder: PayloadDecoder[Event],
      stateSerializer: StateSerializer[State],
      stateDecoder: PayloadDecoder[State],
      tracker: Event => Set[String],
      tagState: Event => Option[(String, Long)],
      untagState: Event => Option[String]
  ) extends EntityDefinition[State, Command, Event, Rejection]

  object PersistentDefinition {

    def untagged[State, Command, Event, Rejection](
        entityType: EntityType,
        eventProcessor: EventProcessor[Command, Event, State, Rejection],
        eventSerializer: EventSerializer[Event],
        eventDecoder: PayloadDecoder[Event],
        stateSerializer: StateSerializer[State],
        stateDecoder: PayloadDecoder[State],
        tracker: Event => Set[String]
    ): PersistentDefinition[State, Command, Event, Rejection] = PersistentDefinition(
      entityType,
      eventProcessor,
      eventSerializer,
      eventDecoder,
      stateSerializer,
      stateDecoder,
      tracker,
      (_: Event) => None,
      (_: Event) => None
    )

  }

  /**
    * A transient implementation of an [[EntityDefinition]]
    *
    * In case of restart or crash, the state will be lost
    */
  final case class TransientEventDefinition[State, Command, Event, Rejection](
      entityType: EntityType,
      eventProcessor: EventProcessor[Command, Event, State, Rejection]
  ) extends EntityDefinition[State, Command, Event, Rejection]
}
