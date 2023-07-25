package ch.epfl.bluebrain.nexus.delta.sourcing.implicits

import ch.epfl.bluebrain.nexus.delta.kernel.search.TimeRange
import ch.epfl.bluebrain.nexus.delta.kernel.search.TimeRange._
import ch.epfl.bluebrain.nexus.delta.sourcing.FragmentEncoder
import doobie.implicits._
import doobie.util.fragment.Fragment
import doobie.postgres.implicits._

trait TimeRangeInstances {

  def createTimeRangeFragmentEncoder(columnName: String): FragmentEncoder[TimeRange] = {
    val column = Fragment.const(columnName)
    FragmentEncoder.instance {
      case Anytime             => None
      case After(value)        => Some(fr"$column >= $value")
      case Before(value)       => Some(fr"$column <= $value")
      case Between(start, end) => Some(fr"$column >= $start and $column <= $end")
    }
  }

}
