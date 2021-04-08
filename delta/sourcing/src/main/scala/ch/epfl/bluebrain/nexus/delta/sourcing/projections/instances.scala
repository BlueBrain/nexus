package ch.epfl.bluebrain.nexus.delta.sourcing.projections

import akka.persistence.query.{NoOffset, Offset, Sequence, TimeBasedUUID}
import io.circe._
import io.circe.generic.extras.Configuration
import io.circe.syntax._

import java.util.UUID

object instances extends AllInstances

trait AllInstances extends CirceInstances with OffsetOrderingInstances

trait CirceInstances {
  implicit private[projections] val config: Configuration = Configuration.default.withDiscriminator("type")

  implicit final val sequenceEncoder: Encoder[Sequence] = Encoder.instance { seq =>
    Json.obj("value" -> seq.value.asJson, "@type" -> "SequenceBasedOffset".asJson)
  }

  implicit final val sequenceDecoder: Decoder[Sequence] = Decoder.instance { cursor =>
    cursor.get[Long]("value").map(value => Sequence(value))
  }

  implicit final val timeBasedUUIDEncoder: Encoder[TimeBasedUUID] = Encoder.instance { uuid =>
    Json.obj("value" -> uuid.value.asJson, "@type" -> "TimeBasedOffset".asJson)
  }

  implicit final val timeBasedUUIDDecoder: Decoder[TimeBasedUUID] = Decoder.instance { cursor =>
    cursor.get[UUID]("value").map(uuid => TimeBasedUUID(uuid))
  }

  implicit final val noOffsetEncoder: Encoder[NoOffset.type] =
    Encoder.instance(_ => Json.obj("@type" -> "NoOffset".asJson))

  implicit final val noOffsetDecoder: Decoder[NoOffset.type] = Decoder.instance { cursor =>
    cursor.as[JsonObject].map(_ => NoOffset)
  }

  implicit final val offsetEncoder: Encoder[Offset] = Encoder.instance {
    case o: Sequence      => o.asJson
    case o: TimeBasedUUID => o.asJson
    case NoOffset         => NoOffset.asJson
  }

  implicit final val offsetDecoder: Decoder[Offset] = {
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

}

trait OffsetOrderingInstances {
  implicit final val offsetOrdering: Ordering[Offset] = {
    case (x: Sequence, y: Sequence)           => x compare y
    case (x: TimeBasedUUID, y: TimeBasedUUID) => x compare y
    case (NoOffset, _)                        => -1
    case (_, NoOffset)                        => 1
    case _                                    => 0
  }
}
