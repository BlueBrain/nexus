package ch.epfl.bluebrain.nexus.delta.sourcing.stream

import scala.concurrent.duration.FiniteDuration

/**
  * Enumeration of rebuild strategies for projections. Projections may need to be rebuilt to ensure a consistent outcome
  * of the data/indices it produces. Ordering of elements in the projection may influence the outcome.
  */
sealed trait RebuildStrategy extends Product with Serializable

object RebuildStrategy {

  /**
    * The projection is never rebuilt.
    */
  final case object Never extends RebuildStrategy
  type Never = Never.type

  /**
    * The projection is rebuilt once after a fixed duration in which no new elements are observed.
    *
    * @param delay
    *   the duration after which the projection should be rebuilt when no new elements are observed
    */
  final case class Once(delay: FiniteDuration) extends RebuildStrategy

}
