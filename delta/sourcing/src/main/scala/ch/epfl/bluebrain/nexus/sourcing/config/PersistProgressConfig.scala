package ch.epfl.bluebrain.nexus.sourcing.config

import scala.concurrent.duration.FiniteDuration

/**
  * Configuration for the persistence of progress of projections
  *
  * @param maxBatchSize the maximum number of items in a batch before persisting progress
  * @param maxTimeWindow the maximum time allowed to pass between persisting the progress
  */
final case class PersistProgressConfig(maxBatchSize: Int, maxTimeWindow: FiniteDuration)
