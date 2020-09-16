package ch.epfl.bluebrain.nexus.delta.sdk.utils

import java.time.Instant
import java.util.concurrent.TimeUnit

import cats.effect.Clock
import monix.bio.UIO

trait IOUtils {

  /**
    * Creates an Instant deferring its evaluation to the to ''clock'' scheduler
    */
  def instant(implicit clock: Clock[UIO[*]]): UIO[Instant] =
    clock.realTime(TimeUnit.MILLISECONDS).map(Instant.ofEpochMilli)

}

object IOUtils extends IOUtils
