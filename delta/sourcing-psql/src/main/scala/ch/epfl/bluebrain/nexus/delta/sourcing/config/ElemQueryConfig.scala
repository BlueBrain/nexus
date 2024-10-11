package ch.epfl.bluebrain.nexus.delta.sourcing.config

import pureconfig.ConfigReader
import pureconfig.generic.semiauto.deriveReader

import scala.concurrent.duration.FiniteDuration

/**
  * Defines how to run streaming queries for elements
  *   - the batch size to apply for each query
  *   - the strategy to adopt when all elems are consumed (stopping, wait for a fixed amount of time or wait for updates
  *     on a project)
  */
sealed trait ElemQueryConfig {

  /**
    * @return
    *   the maximum number of elements to fetch with one query
    */
  def batchSize: Int

}

object ElemQueryConfig {

  /**
    * Will successfully stop the stream when all elems have been consumed
    */
  final case class StopConfig(batchSize: Int) extends ElemQueryConfig

  /**
    * Will pause the stream for a fixed delay before executing the query again
    * @param delay
    *   the amount of time to wait for
    */
  final case class DelayConfig(batchSize: Int, delay: FiniteDuration) extends ElemQueryConfig

  /**
    * Will pause the stream until a resource gets created / updated in the project the stream is running against
    * @param delay
    *   the amount of time to wait for active projects
    */
  final case class PassivationConfig(batchSize: Int, delay: FiniteDuration) extends ElemQueryConfig

  implicit final val queryConfig: ConfigReader[ElemQueryConfig] = {
    val stopConfigReader: ConfigReader[StopConfig]                  = deriveReader[StopConfig]
    val delayConfigReader: ConfigReader[DelayConfig]                = deriveReader[DelayConfig]
    val waitForProjectUpdateReader: ConfigReader[PassivationConfig] = deriveReader[PassivationConfig]
    ConfigReader.fromCursor { cursor =>
      for {
        obj    <- cursor.asObjectCursor
        tpec   <- obj.atKey("type")
        tpe    <- ConfigReader[String].from(tpec)
        config <- tpe match {
                    case "stop"        => stopConfigReader.from(obj)
                    case "delay"       => delayConfigReader.from(obj)
                    case "passivation" => waitForProjectUpdateReader.from(obj)
                  }
      } yield config
    }
  }
}
