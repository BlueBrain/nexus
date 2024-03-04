package ch.epfl.bluebrain.nexus.ship

import ch.epfl.bluebrain.nexus.ship.ImportReport.Count

sealed trait ImportStatus {

  def asCount: Count

}

object ImportStatus {

  case object Success extends ImportStatus {
    override def asCount: Count = Count(1L, 0L)
  }

  case object Dropped extends ImportStatus {

    override def asCount: Count = Count(0L, 1L)

  }

}
