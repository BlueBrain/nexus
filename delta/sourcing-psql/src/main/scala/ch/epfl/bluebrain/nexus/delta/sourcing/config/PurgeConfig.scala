package ch.epfl.bluebrain.nexus.delta.sourcing.config

import scala.concurrent.duration.FiniteDuration

case class PurgeConfig(deleteExpiredEvery: FiniteDuration, ttl: FiniteDuration)
