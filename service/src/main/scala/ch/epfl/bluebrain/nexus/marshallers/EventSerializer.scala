package ch.epfl.bluebrain.nexus.marshallers

import java.nio.charset.Charset

import akka.actor.ExtendedActorSystem
import akka.serialization.SerializerWithStringManifest
import ch.epfl.bluebrain.nexus.acls.AclEvent
import ch.epfl.bluebrain.nexus.config.AppConfig.HttpConfig
import ch.epfl.bluebrain.nexus.config.Settings
import ch.epfl.bluebrain.nexus.permissions.PermissionsEvent
import ch.epfl.bluebrain.nexus.realms.RealmEvent
import ch.epfl.bluebrain.nexus.realms.GrantType.Camel._
import ch.epfl.bluebrain.nexus.utils.Codecs
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto._
import io.circe.parser._
import io.circe.syntax._
import io.circe.{Decoder, Encoder, Printer}

/**
  * A json event serializer for data types that need a human readable representation.
  *
  * @param system the underlying actor system
  */
class EventSerializer(system: ExtendedActorSystem) extends SerializerWithStringManifest with Codecs {
  private val utf8 = Charset.forName("UTF-8")

  private val printer = Printer.noSpaces.copy(dropNullValues = true)

  implicit private[marshallers] val http: HttpConfig = Settings(system).appConfig.http

  implicit private[marshallers] val config: Configuration = Configuration.default.withDiscriminator("@type")

  implicit private[marshallers] val permissionEventEncoder: Encoder[PermissionsEvent] =
    deriveConfiguredEncoder[PermissionsEvent]
  implicit private[marshallers] val permissionEventDecoder: Decoder[PermissionsEvent] =
    deriveConfiguredDecoder[PermissionsEvent]
  implicit private[marshallers] val aclEventEncoder: Encoder[AclEvent]     = deriveConfiguredEncoder[AclEvent]
  implicit private[marshallers] val aclEventDecoder: Decoder[AclEvent]     = deriveConfiguredDecoder[AclEvent]
  implicit private[marshallers] val realmEventEncoder: Encoder[RealmEvent] = deriveConfiguredEncoder[RealmEvent]
  implicit private[marshallers] val realmEventDecoder: Decoder[RealmEvent] = deriveConfiguredDecoder[RealmEvent]

  override val identifier: Int = 1225

  override def manifest(o: AnyRef): String = o match {
    case _: PermissionsEvent => "permissions-event"
    case _: AclEvent         => "acl-event"
    case _: RealmEvent       => "realm-event"
    case other =>
      throw new IllegalArgumentException(
        s"Cannot determine manifest for unknown type: '${other.getClass.getCanonicalName}'"
      )
  }
  override def toBinary(o: AnyRef): Array[Byte] = o match {
    case ev: PermissionsEvent => ev.asJson.printWith(printer).getBytes(utf8)
    case ev: AclEvent         => ev.asJson.printWith(printer).getBytes(utf8)
    case ev: RealmEvent       => ev.asJson.printWith(printer).getBytes(utf8)
    case other =>
      throw new IllegalArgumentException(s"Cannot serialize unknown type: '${other.getClass.getCanonicalName}'")
  }
  override def fromBinary(bytes: Array[Byte], manifest: String): AnyRef = manifest match {
    case "permissions-event" =>
      val str = new String(bytes, utf8)
      decode[PermissionsEvent](str)
        .getOrElse(throw new IllegalArgumentException(s"Cannot deserialize value: '$str' to 'PermissionsEvent'"))
    case "acl-event" =>
      val str = new String(bytes, utf8)
      decode[AclEvent](str)
        .getOrElse(throw new IllegalArgumentException(s"Cannot deserialize value: '$str' to 'AclEvent'"))
    case "realm-event" =>
      val str = new String(bytes, utf8)
      decode[RealmEvent](str)
        .getOrElse(throw new IllegalArgumentException(s"Cannot deserialize value: '$str' to 'RealmEvent'"))
    case other =>
      throw new IllegalArgumentException(s"Cannot deserialize type with unknown manifest: '$other'")
  }
}
