package ch.epfl.bluebrain.nexus.delta.sourcing2

import ch.epfl.bluebrain.nexus.delta.sourcing2.EntityDefinition.PersistentDefinition.StopStrategy
import ch.epfl.bluebrain.nexus.delta.sourcing2.decoder.PayloadDecoder
import ch.epfl.bluebrain.nexus.delta.sourcing2.event.EventSerializer
import ch.epfl.bluebrain.nexus.delta.sourcing2.model.EntityType
import ch.epfl.bluebrain.nexus.delta.sourcing2.state.StateSerializer

import scala.concurrent.duration.FiniteDuration

/**
  * Description of an event source based entity
  */
sealed trait EntityDefinition[State, Command, Event, Rejection] extends Product with Serializable {

  /**
    * The entity type
    */
  def entityType: EntityType

  def processor: EntityProcessor[Command, Event, State, Rejection]

}

object EntityDefinition {

  /**
    * Implementation of an [[EntityDefinition]] which persists the result
    */
  final case class PersistentDefinition[State, Command, Event, Rejection](
      entityType: EntityType,
      processor: EntityProcessor[Command, Event, State, Rejection],
      eventSerializer: EventSerializer[Event],
      eventDecoder: PayloadDecoder[Event],
      stateSerializer: StateSerializer[State],
      stateDecoder: PayloadDecoder[State],
      tracker: Event => Set[String],
      tagState: Event => Option[(String, Long)],
      untagState: Event => Option[String],
      stopStrategy: StopStrategy
  ) extends EntityDefinition[State, Command, Event, Rejection]

  object PersistentDefinition {

    /**
      * A stop strategy for persistent actors
      *
      * @param lapsedSinceLastInteraction
      *   Some(duration) if the actor should stop after no new messages are received in the ''duration'' interval; None
      *   to keep the actor alive
      * @param lapsedSinceRecoveryCompleted
      *   Some(duration) if the actor should stop (and passivate); None to keep the actor alive (and no passivation)
      */
    final case class StopStrategy(
        lapsedSinceLastInteraction: Option[FiniteDuration],
        lapsedSinceRecoveryCompleted: Option[FiniteDuration]
    )

    object StopStrategy {

      /**
        * The actor will never be asked to stop
        */
      def never: StopStrategy = StopStrategy(None, None)
    }

    def untagged[State, Command, Event, Rejection](
        entityType: EntityType,
        eventProcessor: EntityProcessor[Command, Event, State, Rejection],
        eventSerializer: EventSerializer[Event],
        eventDecoder: PayloadDecoder[Event],
        stateSerializer: StateSerializer[State],
        stateDecoder: PayloadDecoder[State],
        tracker: Event => Set[String],
        stopStrategy: StopStrategy
    ): PersistentDefinition[State, Command, Event, Rejection] = PersistentDefinition(
      entityType,
      eventProcessor,
      eventSerializer,
      eventDecoder,
      stateSerializer,
      stateDecoder,
      tracker,
      (_: Event) => None,
      (_: Event) => None,
      stopStrategy
    )
  }

  /**
    * A transient implementation of an [[EntityDefinition]]
    *
    * In case of restart or crash, the state will be lost
    */
  final case class TransientEventDefinition[State, Command, Event, Rejection](
      entityType: EntityType,
      processor: EntityProcessor[Command, Event, State, Rejection]
  ) extends EntityDefinition[State, Command, Event, Rejection]
}
