package ch.epfl.bluebrain.nexus.migration.v1_4.events

import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredEncoder
import io.circe.parser.parse
import io.circe.{Encoder, Json}

import scala.annotation.nowarn

case class EventDeserializationFailed(message: String, json: Json) extends ToMigrateEvent

@nowarn("cat=unused")
object EventDeserializationFailed {

  def apply(message: String, value: String): EventDeserializationFailed =
    EventDeserializationFailed(message, parse(value).getOrElse(Json.Null))

  implicit private val config: Configuration = Configuration.default.withDiscriminator("@type")

  implicit val eventFailedEncoder: Encoder[EventDeserializationFailed] =
    deriveConfiguredEncoder[EventDeserializationFailed]

}
