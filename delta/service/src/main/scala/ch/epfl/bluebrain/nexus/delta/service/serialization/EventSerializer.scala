package ch.epfl.bluebrain.nexus.delta.service.serialization

import java.nio.charset.StandardCharsets

import akka.serialization.SerializerWithStringManifest
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.{CompactedJsonLd, ExpandedJsonLd}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.model.Event
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.{Acl, AclAddress, AclEvent}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.organizations.OrganizationEvent
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.{Permission, PermissionsEvent}
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{ApiMappings, ProjectEvent}
import ch.epfl.bluebrain.nexus.delta.sdk.model.realms.GrantType.Camel._
import ch.epfl.bluebrain.nexus.delta.sdk.model.realms.RealmEvent
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.{IdentityResolution, ResolverEvent, ResolverValue}
import ch.epfl.bluebrain.nexus.delta.sdk.model.resources.ResourceEvent
import ch.epfl.bluebrain.nexus.delta.sdk.model.schemas.SchemaEvent
import ch.epfl.bluebrain.nexus.delta.service.serialization.EventSerializer._
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto._
import io.circe.parser._
import io.circe.syntax._
import io.circe.{Codec, Decoder, DecodingFailure, Encoder}

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
    case _: ResolverEvent     => resolverEventManifest
    case _: ResourceEvent     => resourceEventManifest
    case _: SchemaEvent       => schemaEventManifest
    case _                    => throw new IllegalArgumentException(s"Unknown event type '${o.getClass.getCanonicalName}'")
  }

  override def toBinary(o: AnyRef): Array[Byte] = o match {
    case e: PermissionsEvent  => e.asJson.noSpaces.getBytes(StandardCharsets.UTF_8)
    case e: AclEvent          => e.asJson.noSpaces.getBytes(StandardCharsets.UTF_8)
    case e: RealmEvent        => e.asJson.noSpaces.getBytes(StandardCharsets.UTF_8)
    case e: OrganizationEvent => e.asJson.noSpaces.getBytes(StandardCharsets.UTF_8)
    case e: ProjectEvent      => e.asJson.noSpaces.getBytes(StandardCharsets.UTF_8)
    case e: ResolverEvent     => e.asJson.noSpaces.getBytes(StandardCharsets.UTF_8)
    case e: ResourceEvent     => e.asJson.noSpaces.getBytes(StandardCharsets.UTF_8)
    case e: SchemaEvent       => e.asJson.noSpaces.getBytes(StandardCharsets.UTF_8)
    case _                    => throw new IllegalArgumentException(s"Unknown event type '${o.getClass.getCanonicalName}'")
  }

  override def fromBinary(bytes: Array[Byte], manifest: String): AnyRef = manifest match {
    case `permissionsEventManifest`  => parseAndDecode[PermissionsEvent](bytes, manifest)
    case `aclEventManifest`          => parseAndDecode[AclEvent](bytes, manifest)
    case `realmEventManifest`        => parseAndDecode[RealmEvent](bytes, manifest)
    case `organizationEventManifest` => parseAndDecode[OrganizationEvent](bytes, manifest)
    case `projectEventManifest`      => parseAndDecode[ProjectEvent](bytes, manifest)
    case `resolverEventManifest`     => parseAndDecode[ResolverEvent](bytes, manifest)
    case `resourceEventManifest`     => parseAndDecode[ResourceEvent](bytes, manifest)
    case `schemaEventManifest`       => parseAndDecode[SchemaEvent](bytes, manifest)
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
  final val resolverEventManifest: String     = Resolvers.moduleType
  final val resourceEventManifest: String     = Resources.moduleType
  final val schemaEventManifest: String       = Schemas.moduleType

  implicit final private val configuration: Configuration =
    Configuration.default.withDiscriminator(keywords.tpe)

  implicit final private val subjectCodec: Codec.AsObject[Subject]   = deriveConfiguredCodec[Subject]
  implicit final private val identityCodec: Codec.AsObject[Identity] = deriveConfiguredCodec[Identity]

  implicit final private val apiMappingsDecoder: Decoder[ApiMappings]          =
    Decoder.decodeMap[String, Iri].map(ApiMappings(_))
  implicit final private val apiMappingsEncoder: Encoder.AsObject[ApiMappings] =
    Encoder.encodeMap[String, Iri].contramapObject(_.value)

  implicit final private val aclAddressEncoder: Encoder[AclAddress] = Encoder.encodeString.contramap(_.string)
  implicit final private val aclAddressDecoder: Decoder[AclAddress] = Decoder.decodeString.emap { str =>
    AclAddress.fromString(str).leftMap(_.getMessage)
  }

  final private case class AclEntry(identity: Identity, permissions: Set[Permission])
  implicit final private val aclEntryCodec: Codec.AsObject[AclEntry] = deriveConfiguredCodec[AclEntry]
  implicit final private val aclEncoder: Encoder[Acl]                =
    Encoder.encodeList[AclEntry].contramap(acl => acl.value.toList.map { case (id, perms) => AclEntry(id, perms) })

  implicit private val aclDecoder: Decoder[Acl]                      =
    Decoder.instance { hc =>
      for {
        address <- hc.up.get[AclAddress]("address")
        entries <- hc.as[Vector[AclEntry]]
      } yield Acl(address, entries.map(e => e.identity -> e.permissions).toMap)
    }

  implicit final private val compactedEncoder: Encoder[CompactedJsonLd] = Encoder.instance(_.json)
  implicit final private val compactedDecoder: Decoder[CompactedJsonLd] =
    Decoder.instance { hc =>
      for {
        id  <- hc.up.get[Iri]("id")
        obj <- hc.value.asObject.toRight(DecodingFailure("Expected Json Object", hc.history))
      } yield CompactedJsonLd.unsafe(id, hc.value.topContextValueOrEmpty, obj.remove(keywords.context))
    }

  implicit final private val expandedEncoder: Encoder[ExpandedJsonLd] = Encoder.instance(_.json)
  implicit final private val expandedDecoder: Decoder[ExpandedJsonLd] =
    Decoder.decodeJson.emap(ExpandedJsonLd.expanded(_).leftMap(_.getMessage))

  implicit final private val identityResolutionCodec: Codec.AsObject[IdentityResolution] =
    deriveConfiguredCodec[IdentityResolution]
  implicit final private val resolverValueCodec: Codec.AsObject[ResolverValue]           = deriveConfiguredCodec[ResolverValue]

  implicit final val permissionsEventCodec: Codec.AsObject[PermissionsEvent] = deriveConfiguredCodec[PermissionsEvent]

  implicit final val aclEventDecoder: Decoder[AclEvent]                        = deriveConfiguredDecoder[AclEvent]
  implicit final val aclEventEncoder: Encoder.AsObject[AclEvent]               =
    Encoder.AsObject.instance { ev =>
      deriveConfiguredEncoder[AclEvent].mapJsonObject(_.add("address", ev.address.asJson)).encodeObject(ev)
    }
  implicit final val realmEventCodec: Codec.AsObject[RealmEvent]               = deriveConfiguredCodec[RealmEvent]
  implicit final val organizationEventCodec: Codec.AsObject[OrganizationEvent] =
    deriveConfiguredCodec[OrganizationEvent]
  implicit final val projectEventCodec: Codec.AsObject[ProjectEvent]           = deriveConfiguredCodec[ProjectEvent]
  implicit final val resolverEvent: Codec.AsObject[ResolverEvent]              = deriveConfiguredCodec[ResolverEvent]
  implicit final val resourceEventCodec: Codec.AsObject[ResourceEvent]         = deriveConfiguredCodec[ResourceEvent]
  implicit final val schemaEventCodec: Codec.AsObject[SchemaEvent]             = deriveConfiguredCodec[SchemaEvent]

}
