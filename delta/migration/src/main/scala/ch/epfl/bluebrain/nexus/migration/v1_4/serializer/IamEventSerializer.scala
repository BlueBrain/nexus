package ch.epfl.bluebrain.nexus.migration.v1_4.serializer

import akka.http.scaladsl.model.Uri
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.model.realms.GrantType.Camel._
import ch.epfl.bluebrain.nexus.migration.v1_4.events.EventDeserializationFailed
import ch.epfl.bluebrain.nexus.migration.v1_4.events.iam.{AclEvent, PermissionsEvent, RealmEvent}
import io.circe.Decoder
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto._
import io.circe.parser._

import java.nio.charset.Charset
import scala.annotation.nowarn
import scala.util.Try

/**
  * A json event serializer for data types that need a human readable representation.
  */
@nowarn("cat=unused")
object IamEventSerializer {
  private val utf8 = Charset.forName("UTF-8")

  implicit private val config: Configuration = Configuration.default.withDiscriminator("@type")

  implicit final private val aclAddressDecoder: Decoder[AclAddress] = Decoder.decodeString.emap { str =>
    AclAddress.fromString(str).leftMap(_.getMessage)
  }
  implicit private val uriDecoder: Decoder[Uri]                     = Decoder.decodeString.emapTry(uri => Try(Uri(uri)))

  implicit private val permissionEventDecoder: Decoder[PermissionsEvent] = deriveConfiguredDecoder[PermissionsEvent]
  implicit private val aclEventDecoder: Decoder[AclEvent]                = deriveConfiguredDecoder[AclEvent]
  implicit private val realmEventDecoder: Decoder[RealmEvent]            = deriveConfiguredDecoder[RealmEvent]

  @SuppressWarnings(Array("MethodReturningAny"))
  def fromBinary(bytes: Array[Byte], manifest: String): AnyRef = {
    val str = new String(bytes, utf8)
    manifest match {
      case "permissions-event" =>
        decode[PermissionsEvent](str).valueOr(error =>
          EventDeserializationFailed(s"Cannot deserialize value to 'PermissionsEvent': ${error.show}", str)
        )
      case "acl-event"         =>
        decode[AclEvent](str)
          .valueOr(error => EventDeserializationFailed(s"Cannot deserialize value to 'AclEvent': ${error.show}", str))
      case "realm-event"       =>
        decode[RealmEvent](str)
          .valueOr(error => EventDeserializationFailed(s"Cannot deserialize value to 'RealmEvent': ${error.show}", str))
      case other               =>
        EventDeserializationFailed(s"Cannot deserialize type with unknown manifest: '$other'", str)
    }
  }
}
