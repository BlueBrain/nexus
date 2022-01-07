package ch.epfl.bluebrain.nexus.delta.sourcing2.track

import scala.concurrent.duration.FiniteDuration

final case class TrackConfig(maxSize: Long, cacheTtl: FiniteDuration)
