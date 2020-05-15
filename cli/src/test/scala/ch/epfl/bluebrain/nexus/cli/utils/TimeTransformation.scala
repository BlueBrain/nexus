package ch.epfl.bluebrain.nexus.cli.utils

import java.time.Instant
import java.util.concurrent.TimeUnit.SECONDS

trait TimeTransformation {

  def toNano(instant: Instant): String =
    (SECONDS.toNanos(instant.getEpochSecond) + instant.getNano).toString

}
