package ch.epfl.bluebrain.nexus.delta.service.serialization

import akka.serialization.SerializerWithStringManifest
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.{CompactedJsonLd, ExpandedJsonLd}
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.model.Event
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.{IdentityResolution, ResolverEvent, ResolverValue}
import ch.epfl.bluebrain.nexus.delta.sdk.model.resources.ResourceEvent
import ch.epfl.bluebrain.nexus.delta.service.serialization.EventSerializer._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto._
import io.circe.parser._
import io.circe.syntax._
import io.circe.{Codec, Decoder, DecodingFailure, Encoder}

import java.nio.charset.StandardCharsets
import scala.annotation.nowarn

/**
  * A json serializer for delta [[Event]] types.
  */
class EventSerializer extends SerializerWithStringManifest {

  override def identifier: Int = 453223

  override def manifest(o: AnyRef): String = o match {
    case _: ResolverEvent => resolverEventManifest
    case _: ResourceEvent => resourceEventManifest
    case _                => throw new IllegalArgumentException(s"Unknown event type '${o.getClass.getCanonicalName}'")
  }

  override def toBinary(o: AnyRef): Array[Byte] = o match {
    case e: ResolverEvent => e.asJson.noSpaces.getBytes(StandardCharsets.UTF_8)
    case e: ResourceEvent => e.asJson.noSpaces.getBytes(StandardCharsets.UTF_8)
    case _                => throw new IllegalArgumentException(s"Unknown event type '${o.getClass.getCanonicalName}'")
  }

  override def fromBinary(bytes: Array[Byte], manifest: String): AnyRef = manifest match {
    case `resolverEventManifest` => parseAndDecode[ResolverEvent](bytes, manifest)
    case `resourceEventManifest` => parseAndDecode[ResourceEvent](bytes, manifest)
    case _                       => throw new IllegalArgumentException(s"Unknown manifest '$manifest'")
  }

  private def parseAndDecode[E <: Event: Decoder](bytes: Array[Byte], manifest: String): E = {
    val string = new String(bytes, StandardCharsets.UTF_8)
    decode[E](string)
      .getOrElse(throw new IllegalArgumentException(s"Unable to decode for manifest '$manifest' event '$string'"))
  }
}

@nowarn("cat=unused")
object EventSerializer {

  final val resolverEventManifest: String = Resolvers.moduleType
  final val resourceEventManifest: String = Resources.moduleType

  implicit final private val configuration: Configuration =
    Configuration.default.withDiscriminator(keywords.tpe)

  implicit final private val subjectCodec: Codec.AsObject[Subject]   = deriveConfiguredCodec[Subject]
  implicit final private val identityCodec: Codec.AsObject[Identity] = deriveConfiguredCodec[Identity]

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

  implicit final val resolverEvent: Codec.AsObject[ResolverEvent]      = deriveConfiguredCodec[ResolverEvent]
  implicit final val resourceEventCodec: Codec.AsObject[ResourceEvent] = deriveConfiguredCodec[ResourceEvent]
}
