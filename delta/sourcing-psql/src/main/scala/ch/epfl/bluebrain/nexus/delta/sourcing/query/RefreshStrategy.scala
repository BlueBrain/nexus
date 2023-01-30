package ch.epfl.bluebrain.nexus.delta.sourcing.query

import scala.concurrent.duration.FiniteDuration

/**
  * Strategy to apply when a streaming query consumed all rows
  */
sealed trait RefreshStrategy extends Product with Serializable

object RefreshStrategy {

  /**
    * Completes the stream
    */
  final case object Stop extends RefreshStrategy

  /**
    * Resumes the stream for new events after the given delay
    */
  final case class Delay(value: FiniteDuration) extends RefreshStrategy

}
