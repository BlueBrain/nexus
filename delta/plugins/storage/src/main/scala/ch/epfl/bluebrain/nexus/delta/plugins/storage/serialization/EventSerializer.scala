package ch.epfl.bluebrain.nexus.delta.plugins.storage.serialization

import akka.actor.ExtendedActorSystem
import akka.serialization.SerializerWithStringManifest
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.Files
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.{Digest, FileAttributes, FileEvent}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.serialization.EventSerializer._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.instances._
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.model.Event
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto._
import io.circe.parser._
import io.circe.syntax._
import io.circe.{Codec, Decoder}

import java.nio.charset.StandardCharsets
import scala.annotation.nowarn

/**
  * A json serializer for storages plugins [[Event]] types.
  */
@SuppressWarnings(Array("TryGet"))
@nowarn("cat=unused")
class EventSerializer(system: ExtendedActorSystem) extends SerializerWithStringManifest {

  override def identifier: Int = 453224

  override def manifest(o: AnyRef): String = o match {
    case _: FileEvent => fileEventManifest
    case _            => throw new IllegalArgumentException(s"Unknown event type '${o.getClass.getCanonicalName}'")
  }

  override def toBinary(o: AnyRef): Array[Byte] = o match {
    case e: FileEvent => e.asJson.noSpaces.getBytes(StandardCharsets.UTF_8)
    case _            => throw new IllegalArgumentException(s"Unknown event type '${o.getClass.getCanonicalName}'")
  }

  override def fromBinary(bytes: Array[Byte], manifest: String): AnyRef = manifest match {
    case `fileEventManifest` => parseAndDecode[FileEvent](bytes, manifest)
    case _                   => throw new IllegalArgumentException(s"Unknown manifest '$manifest'")
  }

  private def parseAndDecode[E <: Event: Decoder](bytes: Array[Byte], manifest: String): E = {
    val string = new String(bytes, StandardCharsets.UTF_8)
    decode[E](string)
      .getOrElse(throw new IllegalArgumentException(s"Unable to decode for manifest '$manifest' event '$string'"))
  }

  implicit final private val configuration: Configuration =
    Configuration.default.withDiscriminator(keywords.tpe)

  implicit final private val subjectCodec: Codec.AsObject[Subject]   = deriveConfiguredCodec[Subject]
  implicit final private val identityCodec: Codec.AsObject[Identity] = deriveConfiguredCodec[Identity]

  implicit final private val digestCodec: Codec.AsObject[Digest]                 =
    deriveConfiguredCodec[Digest]
  implicit final private val fileAttributesCodec: Codec.AsObject[FileAttributes] =
    deriveConfiguredCodec[FileAttributes]

  implicit final private val fileEventCodec: Codec.AsObject[FileEvent] =
    deriveConfiguredCodec[FileEvent]
}

object EventSerializer {
  final val fileEventManifest: String = Files.moduleType
}
