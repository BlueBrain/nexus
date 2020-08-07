package ch.epfl.bluebrain.nexus.sourcingnew.projections.config

import scala.concurrent.duration.FiniteDuration

final case class PersistProgressConfig(maxBatchSize: Int, maxTimeWindow: FiniteDuration)
