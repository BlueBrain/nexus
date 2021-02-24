package ch.epfl.bluebrain.nexus.delta.sdk.utils

import akka.persistence.query.{NoOffset, Offset, Sequence, TimeBasedUUID}
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import io.circe.syntax._
import io.circe.{Encoder, Json}

import java.time.Instant

trait OffsetUtils {

  private val NUM_100NS_INTERVALS_SINCE_UUID_EPOCH = 0x01b21dd213814000L

  implicit private val offsetEncoder: Encoder[Offset] =
    Encoder
      .instance[Offset] {
        case Sequence(value)  =>
          Json.obj("@type" -> "SequenceBasedOffset".asJson, "value" -> value.asJson)
        case t: TimeBasedUUID =>
          Json.obj("@type" -> "TimeBasedOffset".asJson, "value" -> t.value.asJson, "instant" -> toInstant(t).asJson)
        case NoOffset         => Json.obj("@type" -> "NoOffset".asJson)
        case _                => Json.obj()
      }

  final val offsetJsonLdEncoder: JsonLdEncoder[Offset] =
    JsonLdEncoder.computeFromCirce(ContextValue(contexts.offset))

  /**
    * Converts the UUIDv1 inside [[TimeBasedUUID]] into an Instant.
    * This implementation is specific for Cassandra TimeUUID, which are not EPOCH based, but 100-nanosecond units since midnight, October 15, 1582 UTC.
    * Due to that reason, a conversion is required
    */
  def toInstant(timeBased: TimeBasedUUID): Instant =
    Instant.ofEpochMilli((timeBased.value.timestamp - NUM_100NS_INTERVALS_SINCE_UUID_EPOCH) / 10000)
}

object OffsetUtils extends OffsetUtils
