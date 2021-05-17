package ch.epfl.bluebrain.nexus.migration.v1_4.serializer

import cats.implicits._
import ch.epfl.bluebrain.nexus.migration.v1_4.events.EventDeserializationFailed
import ch.epfl.bluebrain.nexus.migration.v1_4.events.admin.{OrganizationEvent, ProjectEvent}
import io.circe.Decoder
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredDecoder
import io.circe.parser.decode

import java.nio.charset.Charset
import scala.annotation.nowarn

@nowarn("cat=unused")
object AdminEventSerializer {

  private val utf8 = Charset.forName("UTF-8")

  implicit private val config: Configuration = Configuration.default.withDiscriminator("@type")

  implicit private val projectEventDecoder: Decoder[ProjectEvent]           = deriveConfiguredDecoder[ProjectEvent]
  implicit private val organizationEventDecoder: Decoder[OrganizationEvent] = deriveConfiguredDecoder[OrganizationEvent]

  @SuppressWarnings(Array("MethodReturningAny"))
  def fromBinary(bytes: Array[Byte], manifest: String): AnyRef = {
    val str = new String(bytes, utf8)
    manifest match {
      case "ProjectEvent"      =>
        decode[ProjectEvent](str).valueOr(error =>
          EventDeserializationFailed(s"Cannot deserialize value to 'ProjectEvent': ${error.show}", str)
        )
      case "OrganizationEvent" =>
        decode[OrganizationEvent](str)
          .valueOr(error =>
            EventDeserializationFailed(s"Cannot deserialize value to 'OrganizationEvent': ${error.show}", str)
          )
      case other               =>
        EventDeserializationFailed(s"Cannot deserialize type with unknown manifest: '$other'", str)
    }
  }
}
