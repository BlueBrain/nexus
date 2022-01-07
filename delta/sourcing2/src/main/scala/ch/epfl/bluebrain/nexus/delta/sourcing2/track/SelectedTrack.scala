package ch.epfl.bluebrain.nexus.delta.sourcing2.track

import doobie.util.fragment.Fragment
import doobie.implicits._

/**
  * Selected track
  */
sealed trait SelectedTrack extends Product with Serializable

object SelectedTrack {

  sealed trait ValidTrack extends SelectedTrack {
    def in: Option[Fragment]
  }

  /**
    * Fetches all events
    */
  final case object All extends ValidTrack {
    override def in: Option[Fragment] = None
  }

  /**
    * Fetches events belonging to a single track
    */
  final case class Single(value: Int) extends ValidTrack {
    override def in: Option[Fragment] = Some(fr"ANY(tracks) = $value")
  }

  sealed trait NotFound extends SelectedTrack

  /**
    * The track is not found
    */
  final case object NotFound extends NotFound

}
