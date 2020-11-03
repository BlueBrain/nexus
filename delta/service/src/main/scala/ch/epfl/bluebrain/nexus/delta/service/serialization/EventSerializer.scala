package ch.epfl.bluebrain.nexus.delta.service.serialization

import java.nio.charset.StandardCharsets

import akka.serialization.SerializerWithStringManifest
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.model.Event
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.{Acl, AclAddress, AclEvent}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.organizations.OrganizationEvent
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.{Permission, PermissionsEvent}
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectEvent
import ch.epfl.bluebrain.nexus.delta.sdk.model.realms.GrantType.Camel._
import ch.epfl.bluebrain.nexus.delta.sdk.model.realms.RealmEvent
import ch.epfl.bluebrain.nexus.delta.service.serialization.EventSerializer._
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredCodec
import io.circe.parser._
import io.circe.syntax._
import io.circe.{Codec, Decoder, Encoder}

import scala.annotation.nowarn

/**
  * A json serializer for delta [[Event]] types.
  */
class EventSerializer extends SerializerWithStringManifest {

  override def identifier: Int = 453223

  override def manifest(o: AnyRef): String = o match {
    case _: PermissionsEvent  => permissionsEventManifest
    case _: AclEvent          => aclEventManifest
    case _: RealmEvent        => realmEventManifest
    case _: OrganizationEvent => organizationEventManifest
    case _: ProjectEvent      => projectEventManifest
    case _                    => throw new IllegalArgumentException(s"Unknown event type '${o.getClass.getCanonicalName}'")
  }

  override def toBinary(o: AnyRef): Array[Byte] = o match {
    case e: PermissionsEvent  => e.asJson.noSpaces.getBytes(StandardCharsets.UTF_8)
    case e: AclEvent          => e.asJson.noSpaces.getBytes(StandardCharsets.UTF_8)
    case e: RealmEvent        => e.asJson.noSpaces.getBytes(StandardCharsets.UTF_8)
    case e: OrganizationEvent => e.asJson.noSpaces.getBytes(StandardCharsets.UTF_8)
    case e: ProjectEvent      => e.asJson.noSpaces.getBytes(StandardCharsets.UTF_8)
    case _                    => throw new IllegalArgumentException(s"Unknown event type '${o.getClass.getCanonicalName}'")
  }

  override def fromBinary(bytes: Array[Byte], manifest: String): AnyRef = manifest match {
    case `permissionsEventManifest`  => parseAndDecode[PermissionsEvent](bytes, manifest)
    case `aclEventManifest`          => parseAndDecode[AclEvent](bytes, manifest)
    case `realmEventManifest`        => parseAndDecode[RealmEvent](bytes, manifest)
    case `organizationEventManifest` => parseAndDecode[OrganizationEvent](bytes, manifest)
    case `projectEventManifest`      => parseAndDecode[ProjectEvent](bytes, manifest)
    case _                           => throw new IllegalArgumentException(s"Unknown manifest '$manifest'")
  }

  private def parseAndDecode[E <: Event: Decoder](bytes: Array[Byte], manifest: String): E = {
    val string = new String(bytes, StandardCharsets.UTF_8)
    decode[E](string)
      .getOrElse(throw new IllegalArgumentException(s"Unable to decode for manifest '$manifest' event '$string'"))
  }
}

@nowarn("cat=unused")
object EventSerializer {

  final val permissionsEventManifest: String  = Permissions.moduleType
  final val aclEventManifest: String          = Acls.moduleType
  final val realmEventManifest: String        = Realms.moduleType
  final val organizationEventManifest: String = Organizations.moduleType
  final val projectEventManifest: String      = Projects.moduleType

  implicit final private val configuration: Configuration =
    Configuration.default.withStrictDecoding.withDiscriminator(keywords.tpe)

  implicit final private val subjectCodec: Codec.AsObject[Subject]   = deriveConfiguredCodec[Subject]
  implicit final private val identityCodec: Codec.AsObject[Identity] = deriveConfiguredCodec[Identity]

  implicit final private val aclAddressEncoder: Encoder[AclAddress] = Encoder.encodeString.contramap(_.string)
  implicit final private val aclAddressDecoder: Decoder[AclAddress] = Decoder.decodeString.emap { str =>
    AclAddress.fromString(str).leftMap(_.getMessage)
  }

  final private case class AclEntry(identity: Identity, permissions: Set[Permission])
  final private val aclEntryCodec                     = deriveConfiguredCodec[AclEntry]
  implicit final private val aclEncoder: Encoder[Acl] =
    Encoder.encodeList(aclEntryCodec).contramap(acl => acl.value.toList.map { case (id, perms) => AclEntry(id, perms) })
  implicit final private val aclDecoder: Decoder[Acl] = Decoder.decodeList[AclEntry](aclEntryCodec).map { entries =>
    Acl(entries.map(e => e.identity -> e.permissions).toMap)
  }

  implicit final val permissionsEventCodec: Codec.AsObject[PermissionsEvent] = deriveConfiguredCodec[PermissionsEvent]
  implicit final val aclEventCodec: Codec.AsObject[AclEvent]                 = deriveConfiguredCodec[AclEvent]
  implicit final val realmEventCodec: Codec.AsObject[RealmEvent]             = deriveConfiguredCodec[RealmEvent]
  implicit final val organizationEvent: Codec.AsObject[OrganizationEvent]    = deriveConfiguredCodec[OrganizationEvent]
  implicit final val projectEvent: Codec.AsObject[ProjectEvent]              = deriveConfiguredCodec[ProjectEvent]
}
