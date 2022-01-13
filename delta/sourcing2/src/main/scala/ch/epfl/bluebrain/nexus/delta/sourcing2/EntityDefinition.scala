package ch.epfl.bluebrain.nexus.delta.sourcing2

import ch.epfl.bluebrain.nexus.delta.sourcing2.model.EntityType

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
      tracker: Event => Set[String],
      tagState: Event => Option[(String, Long)],
      untagState: Event => Option[String]
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
        processor: EntityProcessor[Command, Event, State, Rejection],
        tracker: Event => Set[String]
    ): PersistentDefinition[State, Command, Event, Rejection] = PersistentDefinition(
      entityType,
      processor,
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
      processor: EntityProcessor[Command, Event, State, Rejection]
  ) extends EntityDefinition[State, Command, Event, Rejection]
}
