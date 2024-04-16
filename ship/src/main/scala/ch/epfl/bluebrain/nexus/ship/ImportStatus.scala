package ch.epfl.bluebrain.nexus.ship

import ch.epfl.bluebrain.nexus.ship.ImportReport.Statistics

sealed trait ImportStatus {

  def asCount: Statistics

}

object ImportStatus {

  case object Success extends ImportStatus {
    override def asCount: Statistics = Statistics(1L, 0L)
  }

  case object Dropped extends ImportStatus {

    override def asCount: Statistics = Statistics(0L, 1L)

  }

}
