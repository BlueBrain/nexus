package ch.epfl.bluebrain.nexus.delta.sourcing.config

import ch.epfl.bluebrain.nexus.delta.sourcing.query.RefreshStrategy
import pureconfig.ConfigReader

import scala.concurrent.duration.FiniteDuration

/**
  * Defines parameters for streaming queries
  *
  * @param batchSize
  *   the maximum number of elements to fetch with one query
  * @param refreshStrategy
  *   complete the stream when all the rows have been consumed or delay and re-execute the query
  */
final case class QueryConfig(batchSize: Int, refreshStrategy: RefreshStrategy)

object QueryConfig {
  implicit final val queryConfig: ConfigReader[QueryConfig] =
    ConfigReader.fromCursor { cursor =>
      for {
        obj             <- cursor.asObjectCursor
        batchSizeK      <- obj.atKey("batch-size")
        batchSize       <- ConfigReader[Int].from(batchSizeK)
        refreshK        <- obj.atKey("refresh-interval")
        refreshInterval <- ConfigReader[FiniteDuration].from(refreshK)
      } yield QueryConfig(batchSize, RefreshStrategy.Delay(refreshInterval))
    }
}
