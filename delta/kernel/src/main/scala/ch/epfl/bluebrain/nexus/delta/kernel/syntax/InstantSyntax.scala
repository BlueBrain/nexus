package ch.epfl.bluebrain.nexus.delta.kernel.syntax

import java.time.Instant
import scala.concurrent.duration._

trait InstantSyntax {
  implicit final def instantSyntax(instant: Instant): InstantOps = new InstantOps(instant)
}

final class InstantOps(private val instant: Instant) extends AnyVal {

  /**
    * @return the duration between two instants.
    */
  def diff(other: Instant): FiniteDuration =
    Math.abs(instant.toEpochMilli - other.toEpochMilli).millis
}
