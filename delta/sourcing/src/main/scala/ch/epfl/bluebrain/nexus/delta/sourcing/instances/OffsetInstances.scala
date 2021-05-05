package ch.epfl.bluebrain.nexus.delta.sourcing.instances

import akka.persistence.query.{NoOffset, Offset}
import ch.epfl.bluebrain.nexus.delta.sourcing.OffsetUtils
import io.circe.{Decoder, Encoder}

trait OffsetInstances {
  implicit val noOffsetEncoder: Encoder[NoOffset.type] = OffsetUtils.noOffsetEncoder
  implicit val offsetEncoder: Encoder[Offset]          = OffsetUtils.offsetEncoder
  implicit val offsetDecoder: Decoder[Offset]          = OffsetUtils.offsetDecoder
  implicit val offsetOrdering: Ordering[Offset]        = OffsetUtils.offsetOrdering
}

object OffsetInstances extends OffsetInstances
