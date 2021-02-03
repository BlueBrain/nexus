package ch.epfl.bluebrain.nexus.migration.replay

import scala.concurrent.duration._

/**
  * Bucket size for partitioning events
  *
  * Based on [[akka.persistence.cassandra.BucketSize]]
  */
sealed trait BucketSize {
  val durationMillis: Long
}

object BucketSize {

  case object Day extends BucketSize {
    override val durationMillis: Long = 1.day.toMillis
  }

  case object Hour extends BucketSize {
    override val durationMillis: Long = 1.hour.toMillis
  }

  case object Minute extends BucketSize {
    override val durationMillis: Long = 1.minute.toMillis
  }

  case object Second extends BucketSize {
    override val durationMillis: Long = 1.second.toMillis
  }

  def fromString(value: String): BucketSize =
    Vector(Day, Hour, Minute, Second)
      .find(_.toString.toLowerCase == value.toLowerCase)
      .getOrElse(throw new IllegalArgumentException("Invalid value for bucket size: " + value))

}
