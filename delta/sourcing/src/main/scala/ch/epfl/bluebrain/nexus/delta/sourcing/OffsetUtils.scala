package ch.epfl.bluebrain.nexus.delta.sourcing

import akka.persistence.query.{NoOffset, Offset, Sequence, TimeBasedUUID}
import com.datastax.oss.driver.api.core.uuid.Uuids
import io.circe._
import io.circe.syntax._

import java.time.format.DateTimeFormatter
import java.time.{Instant, LocalDateTime, ZoneOffset}
import java.util.UUID

trait OffsetUtils {

  private val timestampFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss:SSS")

  val noOffsetEncoder: Encoder[NoOffset.type] =
    Encoder.instance(_ => Json.obj("@type" -> "NoOffset".asJson))

  val offsetEncoder: Encoder[Offset]          =
    Encoder.instance[Offset] {
      case Sequence(value)  =>
        Json.obj("@type" -> "SequenceBasedOffset".asJson, "value" -> value.asJson)
      case t: TimeBasedUUID =>
        Json.obj("@type" -> "TimeBasedOffset".asJson, "value" -> t.value.asJson, "instant" -> toInstant(t).asJson)
      case NoOffset         => noOffsetEncoder(NoOffset)
      case _                => Json.obj()
    }

  val offsetDecoder: Decoder[Offset] = {
    implicit val timeBasedUUIDDecoder: Decoder[TimeBasedUUID] = Decoder.instance { cursor =>
      cursor.get[UUID]("value").map(uuid => TimeBasedUUID(uuid))
    }

    implicit val sequenceDecoder: Decoder[Sequence]      = Decoder.instance { cursor =>
      cursor.get[Long]("value").map(value => Sequence(value))
    }
    implicit val noOffsetDecoder: Decoder[NoOffset.type] = Decoder.instance { cursor =>
      cursor.as[JsonObject].map(_ => NoOffset)
    }

    Decoder.instance { cursor =>
      cursor.get[String]("@type").flatMap {
        case "SequenceBasedOffset" => cursor.as[Sequence]
        case "TimeBasedOffset"     => cursor.as[TimeBasedUUID]
        case "NoOffset"            => cursor.as[NoOffset.type]
        //       $COVERAGE-OFF$
        case other                 => Left(DecodingFailure(s"Unknown discriminator value '$other'", cursor.history))
        //       $COVERAGE-ON$
      }
    }
  }

  val offsetOrdering: Ordering[Offset] = {
    case (x: Sequence, y: Sequence)           => x compare y
    case (x: TimeBasedUUID, y: TimeBasedUUID) => x compare y
    case (NoOffset, _)                        => -1
    case (_, NoOffset)                        => 1
    case _                                    => 0
  }

  /**
    * Converts the UUIDv1 inside [[TimeBasedUUID]] into an Instant.
    * This implementation is specific for Cassandra TimeUUID, which are not EPOCH based, but 100-nanosecond units since midnight, October 15, 1582 UTC.
    * Due to that reason, a conversion is required
    */
  def toInstant(timeBased: TimeBasedUUID): Instant =
    Instant.ofEpochMilli(Uuids.unixTimestamp(timeBased.value))

  /**
    * Format the offset in a meaningful way as text
    */
  def asString(offset: Offset): String =
    offset match {
      case NoOffset             => "NoOffset"
      case Sequence(value)      => value.toString
      case TimeBasedUUID(value) =>
        val time = LocalDateTime.ofInstant(Instant.ofEpochMilli(Uuids.unixTimestamp(value)), ZoneOffset.UTC)
        s"$value (${timestampFormatter.format(time)})"
    }

}

object OffsetUtils extends OffsetUtils
