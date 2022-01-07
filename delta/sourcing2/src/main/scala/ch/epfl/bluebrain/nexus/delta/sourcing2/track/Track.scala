package ch.epfl.bluebrain.nexus.delta.sourcing2.track

/**
  * Track to query rows from
  */
sealed trait Track extends Product with Serializable

object Track {

  /**
    * Fetches rows from all tracks
    */
  case object All extends Track

  /**
    * Fetches rows from the given track
    */
  case class Single(value: String) extends Track

}
