package ch.epfl.bluebrain.nexus.delta.sourcing.projections

import akka.persistence.query.{NoOffset, Offset, Sequence, TimeBasedUUID}
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.OffsetSyntax.OffsetOpts
import com.datastax.oss.driver.api.core.uuid.Uuids

import java.time.format.DateTimeFormatter
import java.time.{Instant, LocalDateTime, ZoneOffset}
import scala.math.Ordering.Implicits._

object syntax extends AllSyntax

trait AllSyntax extends OffsetSyntax

trait OffsetSyntax {
  implicit final def offsetSyntax(value: Offset): OffsetOpts = new OffsetOpts(value)
}

object OffsetSyntax extends OffsetOrderingInstances {

  private val timestampFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss:SSS")

  final class OffsetOpts(private val value: Offset) extends AnyVal {

    /**
      * Offset comparison
      *
      * @param offset the offset to compare against the ''value''
      * @return true when ''value'' is greater than the passed ''offset'' or when offset is ''NoOffset'', false otherwise
      */
    def gt(offset: Offset): Boolean =
      offset == NoOffset || value > offset

    /**
      * Format the offset in a meaningful way as text
      */
    def asString: String =
      value match {
        case NoOffset             => "NoOffset"
        case Sequence(value)      => value.toString
        case TimeBasedUUID(value) =>
          val time = LocalDateTime.ofInstant(Instant.ofEpochMilli(Uuids.unixTimestamp(value)), ZoneOffset.UTC)
          s"$value (${timestampFormatter.format(time)})"
      }
  }
}
