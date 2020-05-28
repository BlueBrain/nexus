package ch.epfl.bluebrain.nexus.sourcing.projections

import java.time.Instant

import akka.persistence.query.{NoOffset, Offset, TimeBasedUUID}
import ch.epfl.bluebrain.nexus.sourcing.projections.OffsetSyntax.OffsetOpts
import ch.epfl.bluebrain.nexus.sourcing.projections.TimeBasedUUIDSyntax.TimeBasedUUIDOps

import scala.math.Ordering.Implicits._

object syntax extends AllSyntax

trait AllSyntax extends OffsetSyntax with TimeBasedUUIDSyntax

trait TimeBasedUUIDSyntax {
  implicit final def timeBasedUUIDSyntax(value: TimeBasedUUID): TimeBasedUUIDOps = new TimeBasedUUIDOps(value)
}

object TimeBasedUUIDSyntax {
  private val NUM_100NS_INTERVALS_SINCE_UUID_EPOCH = 0X01B21DD213814000L

  final class TimeBasedUUIDOps(private val timeBased: TimeBasedUUID) extends AnyVal {

    /**
      * Converts the UUIDv1 inside [[TimeBasedUUID]] into an Instant.
      * This implementation is specific for Cassandra TimeUUID, which are not EPOCH based, but 100-nanosecond units since midnight, October 15, 1582 UTC.
      * Due to that reason, a conversion is required
      */
    def asInstant: Instant =
      Instant.ofEpochMilli((timeBased.value.timestamp - NUM_100NS_INTERVALS_SINCE_UUID_EPOCH) / 10000)
  }
}

trait OffsetSyntax {
  implicit final def offsetSyntax(value: Offset): OffsetOpts = new OffsetOpts(value)
}

object OffsetSyntax extends OffsetOrderingInstances {

  final class OffsetOpts(private val value: Offset) extends AnyVal {

    /**
      * Offset comparison
      *
      * @param offset the offset to compare against the ''value''
      * @return true when ''value'' is greater than the passed ''offset'' or when offset is ''NoOffset'', false otherwise
      **/
    def gt(offset: Offset): Boolean =
      offset == NoOffset || value > offset
  }
}
