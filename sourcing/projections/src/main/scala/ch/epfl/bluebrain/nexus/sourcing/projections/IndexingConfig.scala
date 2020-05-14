package ch.epfl.bluebrain.nexus.sourcing.projections

import ch.epfl.bluebrain.nexus.sourcing.RetryStrategyConfig
import ch.epfl.bluebrain.nexus.sourcing.projections.IndexingConfig.PersistProgressConfig

import scala.concurrent.duration.FiniteDuration

/**
  * Indexing configuration
  *
  * @param batch          the maximum number of events taken on each batch
  * @param batchTimeout   the maximum amount of time to wait for the number of events to be taken on each batch
  * @param retry          the retry configuration when indexing failures
  * @param progress       the retry configuration when indexing failures
  */
final case class IndexingConfig(
    batch: Int,
    batchTimeout: FiniteDuration,
    retry: RetryStrategyConfig,
    progress: PersistProgressConfig
)

object IndexingConfig {

  /**
    * Indexing progress persistence settings
    *
    * @param persistAfterProcessed the number of events after which the indexing progress is being persisted
    * @param maxTimeWindow the amount of time after which the indexing progress is being persisted
    */
  final case class PersistProgressConfig(persistAfterProcessed: Int, maxTimeWindow: FiniteDuration)

  implicit def toPersistProgress(implicit config: IndexingConfig): PersistProgressConfig = config.progress
}
