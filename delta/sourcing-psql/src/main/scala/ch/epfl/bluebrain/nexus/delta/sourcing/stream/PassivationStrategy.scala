package ch.epfl.bluebrain.nexus.delta.sourcing.stream

import scala.concurrent.duration.FiniteDuration

/**
  * Enumeration of passivation strategies for projections.
  */
sealed trait PassivationStrategy extends Product with Serializable

object PassivationStrategy {

  /**
    * Never passivate the projection.
    */
  final case object Never extends PassivationStrategy
  type Never = Never.type

  /**
    * Passivate the projection after a fixed duration of inactivity.
    *
    * @param interval
    *   the duration after which the projection should be passivated when no new elements are observed
    */
  final case class After(interval: FiniteDuration) extends PassivationStrategy

}
