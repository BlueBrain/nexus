package ch.epfl.bluebrain.nexus.delta.plugins.storage.serialization

import akka.actor.ExtendedActorSystem
import akka.serialization.SerializerWithStringManifest
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.Files
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.{Digest, FileAttributes, FileEvent}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.serialization.EventSerializer._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.instances._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.Storages
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StoragesConfig.StorageTypeConfig
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.{Secret, Storage, StorageEvent, StorageType, StorageValue}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.model.Event
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto._
import io.circe.parser._
import io.circe.syntax._
import io.circe.{Codec, Decoder, Encoder, Json}
import software.amazon.awssdk.regions.Region

import java.nio.charset.StandardCharsets
import java.nio.file.Path
import scala.annotation.nowarn
import scala.util.Try
import pureconfig._

/**
  * A json serializer for storages plugins [[Event]] types.
  */
@SuppressWarnings(Array("OptionGet"))
@nowarn("cat=unused")
class EventSerializer(system: ExtendedActorSystem) extends SerializerWithStringManifest {

  private val crypto =
    ConfigSource
      .fromConfig(system.settings.config)
      .at("storage")
      .at("storages")
      .loadOrThrow[StorageTypeConfig]
      .encryption
      .crypto

  override def identifier: Int = 453224

  override def manifest(o: AnyRef): String = o match {
    case _: StorageEvent => storageEventManifest
    case _: FileEvent    => fileEventManifest
    case _               => throw new IllegalArgumentException(s"Unknown event type '${o.getClass.getCanonicalName}'")
  }

  override def toBinary(o: AnyRef): Array[Byte] = o match {
    case e: StorageEvent => e.asJson.noSpaces.getBytes(StandardCharsets.UTF_8)
    case e: FileEvent    => e.asJson.noSpaces.getBytes(StandardCharsets.UTF_8)
    case _               => throw new IllegalArgumentException(s"Unknown event type '${o.getClass.getCanonicalName}'")
  }

  override def fromBinary(bytes: Array[Byte], manifest: String): AnyRef = manifest match {
    case `storageEventManifest` => parseAndDecode[StorageEvent](bytes, manifest)
    case `fileEventManifest`    => parseAndDecode[FileEvent](bytes, manifest)
    case _                      => throw new IllegalArgumentException(s"Unknown manifest '$manifest'")
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
  implicit final private val pathEncoder: Encoder[Path]              = Encoder.encodeString.contramap(_.toString)
  implicit final private val pathDecoder: Decoder[Path]              = Decoder.decodeString.emapTry(str => Try(Path.of(str)))
  implicit final private val regionEncoder: Encoder[Region]          = Encoder.encodeString.contramap(_.toString)
  implicit final private val regionDecoder: Decoder[Region]          = Decoder.decodeString.map(Region.of)

  implicit val jsonSecretEncryptEncoder: Encoder[Secret[Json]] =
    Encoder.encodeJson.contramap(Storage.encryptSource(_, crypto).toOption.get)

  implicit val stringSecretEncryptEncoder: Encoder[Secret[String]] = Encoder.encodeString.contramap {
    case Secret(value) => crypto.encrypt(value).toOption.get
  }

  implicit val jsonSecretDecryptDecoder: Decoder[Secret[Json]] =
    Decoder.decodeJson.emap(Storage.decryptSource(_, crypto))

  implicit val stringSecretEncryptDecoder: Decoder[Secret[String]] =
    Decoder.decodeString.map(str => Secret(crypto.decrypt(str).toOption.get))

  implicit final private val digestCodec: Codec.AsObject[Digest]                 =
    deriveConfiguredCodec[Digest]
  implicit final private val fileAttributesCodec: Codec.AsObject[FileAttributes] =
    deriveConfiguredCodec[FileAttributes]

  implicit val storageTypeEncoder: Encoder[StorageType] = Encoder.encodeString.contramap(_.iri.toString)
  implicit val storageTypeDecoder: Decoder[StorageType] = Iri.iriDecoder.emap(StorageType.apply)

  implicit final private val storageValueCodec: Codec.AsObject[StorageValue] =
    deriveConfiguredCodec[StorageValue]
  implicit final private val storageEventCodec: Codec.AsObject[StorageEvent] =
    deriveConfiguredCodec[StorageEvent]

  implicit final private val fileEventCodec: Codec.AsObject[FileEvent] =
    deriveConfiguredCodec[FileEvent]
}

object EventSerializer {
  final val storageEventManifest: String = Storages.moduleType
  final val fileEventManifest: String    = Files.moduleType
}
