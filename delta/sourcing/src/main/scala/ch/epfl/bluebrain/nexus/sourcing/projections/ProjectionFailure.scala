package ch.epfl.bluebrain.nexus.sourcing.projections

import akka.persistence.query.Offset

import java.time.Instant

/**
  * Describes a projection failure
  * @param offset    the offset when the projection failed
  * @param timestamp the moment it failed
  * @param value     the value when it failed
  * @param errorType a type of error
  */
final case class ProjectionFailure[A](offset: Offset, timestamp: Instant, value: Option[A], errorType: String)
