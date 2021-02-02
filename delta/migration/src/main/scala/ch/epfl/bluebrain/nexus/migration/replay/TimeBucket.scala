package ch.epfl.bluebrain.nexus.migration.replay

import cats.effect.Clock
import com.datastax.oss.driver.api.core.uuid.Uuids
import monix.bio.UIO

import java.util.UUID
import java.util.concurrent.TimeUnit

/**
  * Time bucket for partitioning events in Cassandra
  *
  * Based on [[akka.persistence.cassandra.journal.TimeBucket]]
  */
final class TimeBucket private (val key: Long, val bucketSize: BucketSize)(implicit clock: Clock[UIO]) {
  def inPast: UIO[Boolean] =
    clock.realTime(TimeUnit.MILLISECONDS).map { now =>
      key < TimeBucket.roundDownBucketSize(now, bucketSize)
    }

  def isCurrent: UIO[Boolean] = clock.realTime(TimeUnit.MILLISECONDS).map { now =>
    now >= key && now < (key + bucketSize.durationMillis)
  }

  def within(uuid: UUID): Boolean = {
    val when = Uuids.unixTimestamp(uuid)
    when >= key && when < (key + bucketSize.durationMillis)
  }

  def next(): TimeBucket =
    new TimeBucket(key + bucketSize.durationMillis, bucketSize)

  def previous(steps: Int): TimeBucket =
    if (steps == 0) this
    else new TimeBucket(key - steps * bucketSize.durationMillis, bucketSize)

  def >(other: TimeBucket): Boolean =
    key > other.key

  def <(other: TimeBucket): Boolean =
    key < other.key

  def <=(other: TimeBucket): Boolean =
    key <= other.key

}

object TimeBucket {

  def apply(timeuuid: UUID, bucketSize: BucketSize): TimeBucket =
    apply(Uuids.unixTimestamp(timeuuid), bucketSize)

  def apply(epochTimestamp: Long, bucketSize: BucketSize): TimeBucket =
    // round down to bucket size so the times are deterministic
    new TimeBucket(roundDownBucketSize(epochTimestamp, bucketSize), bucketSize)

  private def roundDownBucketSize(time: Long, bucketSize: BucketSize): Long = {
    val key: Long = time / bucketSize.durationMillis
    key * bucketSize.durationMillis
  }
}
