package ch.epfl.bluebrain.nexus.delta.plugins.storages.serialization

import akka.serialization.SerializerWithStringManifest
import ch.epfl.bluebrain.nexus.delta.plugins.storages.serialization.EventSerializer._
import ch.epfl.bluebrain.nexus.delta.plugins.storages.storage.Storages
import ch.epfl.bluebrain.nexus.delta.plugins.storages.storage.model.{StorageEvent, StorageValue}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.model.Event
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto._
import io.circe.parser._
import io.circe.syntax._
import io.circe.{Codec, Decoder, Encoder}

import java.nio.charset.StandardCharsets
import java.nio.file.Path
import scala.annotation.nowarn
import scala.util.Try

/**
  * A json serializer for storages plugins [[Event]] types.
  */
class EventSerializer extends SerializerWithStringManifest {

  override def identifier: Int = 453224

  override def manifest(o: AnyRef): String = o match {
    case _: StorageEvent => storageEventManifest
    case _               => throw new IllegalArgumentException(s"Unknown event type '${o.getClass.getCanonicalName}'")
  }

  override def toBinary(o: AnyRef): Array[Byte] = o match {
    case e: StorageEvent => e.asJson.noSpaces.getBytes(StandardCharsets.UTF_8)
    case _               => throw new IllegalArgumentException(s"Unknown event type '${o.getClass.getCanonicalName}'")
  }

  override def fromBinary(bytes: Array[Byte], manifest: String): AnyRef = manifest match {
    case `storageEventManifest` => parseAndDecode[StorageEvent](bytes, manifest)
    case _                      => throw new IllegalArgumentException(s"Unknown manifest '$manifest'")
  }

  private def parseAndDecode[E <: Event: Decoder](bytes: Array[Byte], manifest: String): E = {
    val string = new String(bytes, StandardCharsets.UTF_8)
    decode[E](string)
      .getOrElse(throw new IllegalArgumentException(s"Unable to decode for manifest '$manifest' event '$string'"))
  }
}

@nowarn("cat=unused")
object EventSerializer {

  final val storageEventManifest: String = Storages.moduleType

  implicit final private val configuration: Configuration =
    Configuration.default.withDiscriminator(keywords.tpe)

  implicit final private val subjectCodec: Codec.AsObject[Subject]                          = deriveConfiguredCodec[Subject]
  implicit final private val identityCodec: Codec.AsObject[Identity]                        = deriveConfiguredCodec[Identity]
  implicit final private val pathEncoder: Encoder[Path]                                     = Encoder.encodeString.contramap(_.toString)
  implicit final private val pathDecoder: Decoder[Path]                                     = Decoder.decodeString.emapTry(str => Try(Path.of(str)))
  implicit final private val storageValueCodec: Codec.AsObject[StorageValue]                = deriveConfiguredCodec[StorageValue]
  implicit final private[serialization] val storageEventCodec: Codec.AsObject[StorageEvent] =
    deriveConfiguredCodec[StorageEvent]

}
