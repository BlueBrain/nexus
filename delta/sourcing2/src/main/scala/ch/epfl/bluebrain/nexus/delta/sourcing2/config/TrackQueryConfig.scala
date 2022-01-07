package ch.epfl.bluebrain.nexus.delta.sourcing2.config

import scala.concurrent.duration.FiniteDuration

/**
  * Configuration for track queries
  * @param batchSize
  *   Maximum of rows
  * @param refreshInterval
  *   New rows are retrieved (polled) with this interval
  */
case class TrackQueryConfig(batchSize: Int, refreshInterval: FiniteDuration)
