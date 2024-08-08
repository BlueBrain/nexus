package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client

import io.circe.Codec
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredCodec

final case class PointInTime(id: String) extends AnyVal with Serializable

object PointInTime {
  implicit private val config: Configuration          = Configuration.default
  implicit val pointInTimeDecoder: Codec[PointInTime] = deriveConfiguredCodec[PointInTime]
}
