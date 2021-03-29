package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.serialization

import akka.actor.ExtendedActorSystem
import akka.serialization.SerializerWithStringManifest
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.kernel.Secret
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.CompositeViews
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeView.RebuildStrategy
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewSource.AccessToken
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model._
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.serialization.EventSerializer.compositeViewsEventManifest
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.sdk.crypto.EncryptionConfig
import ch.epfl.bluebrain.nexus.delta.sdk.model.Event
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredCodec
import io.circe.parser.decode
import io.circe.syntax._
import io.circe.{Codec, Decoder, Encoder, Printer}
import pureconfig.ConfigSource
import pureconfig.generic.auto._

import java.nio.charset.StandardCharsets
import scala.annotation.nowarn
import scala.concurrent.duration.{Duration, FiniteDuration}

/**
  * A json serializer for Composite view plugin [[Event]] types.
  */
@nowarn("cat=unused")
@SuppressWarnings(Array("UnusedMethodParameter"))
class EventSerializer(system: ExtendedActorSystem) extends SerializerWithStringManifest {

  private val crypto =
    ConfigSource
      .fromConfig(system.settings.config)
      .at("app")
      .at("encryption")
      .loadOrThrow[EncryptionConfig]
      .crypto

  private val printer: Printer = Printer.noSpaces.copy(dropNullValues = true)

  override def identifier: Int = 453227

  override def manifest(o: AnyRef): String = o match {
    case _: CompositeViewEvent => compositeViewsEventManifest
    case _                     =>
      throw new IllegalArgumentException(
        s"Unknown event type '${o.getClass.getCanonicalName}', expected CompositeViewEvent"
      )
  }

  override def toBinary(o: AnyRef): Array[Byte] = o match {
    case e: CompositeViewEvent => printer.print(e.asJson).getBytes(StandardCharsets.UTF_8)
    case _                     =>
      throw new IllegalArgumentException(
        s"Unknown event type '${o.getClass.getCanonicalName}', expected CompositeViewEvent"
      )
  }

  override def fromBinary(bytes: Array[Byte], manifest: String): AnyRef = manifest match {
    case `compositeViewsEventManifest` => parseAndDecode[CompositeViewEvent](bytes, manifest)
    case _                             =>
      throw new IllegalArgumentException(s"Unknown manifest '$manifest', expected '$compositeViewsEventManifest'")
  }

  private def parseAndDecode[E <: Event: Decoder](bytes: Array[Byte], manifest: String): E = {
    val string = new String(bytes, StandardCharsets.UTF_8)
    decode[E](string)
      .getOrElse(throw new IllegalArgumentException(s"Unable to decode for manifest '$manifest' event '$string'"))
  }

  implicit final private val configuration: Configuration =
    Configuration.default.withDiscriminator(keywords.tpe)

  implicit private val stringSecretEncryptEncoder: Encoder[Secret[String]] = Encoder.encodeString.contramap {
    case Secret(value) => crypto.encrypt(value).get
  }

  implicit private val stringSecretDecryptDecoder: Decoder[Secret[String]] =
    Decoder.decodeString.emap(str => crypto.decrypt(str).map(Secret(_)).toEither.leftMap(_.getMessage))

  implicit final private val subjectCodec: Codec.AsObject[Subject]         = deriveConfiguredCodec[Subject]
  implicit final private val identityCodec: Codec.AsObject[Identity]       = deriveConfiguredCodec[Identity]
  implicit final private val accessTokenCodec: Codec.AsObject[AccessToken] = deriveConfiguredCodec[AccessToken]

  implicit val finiteDurationEncoder: Encoder[FiniteDuration] = Encoder.encodeString.contramap(_.toString())
  implicit val finiteDurationDecoder: Decoder[FiniteDuration] = Decoder.decodeString.emap { s =>
    Duration(s) match {
      case finite: FiniteDuration => Right(finite)
      case _                      => Left(s"$s is not a valid FinalDuration")
    }
  }

  implicit final private val rebuildStrategyCodec: Codec.AsObject[RebuildStrategy] =
    deriveConfiguredCodec[RebuildStrategy]

  implicit final private val compositeViewSourceTypeCodec: Codec.AsObject[SourceType] =
    deriveConfiguredCodec[SourceType]

  implicit final private val compositeViewProjectionTypeCodec: Codec.AsObject[ProjectionType] =
    deriveConfiguredCodec[ProjectionType]

  implicit final private val compositeViewProjectionCodec: Codec.AsObject[CompositeViewProjection] =
    deriveConfiguredCodec[CompositeViewProjection]

  implicit final private val compositeViewSourceCodec: Codec.AsObject[CompositeViewSource] =
    deriveConfiguredCodec[CompositeViewSource]

  implicit final private val compositeViewValueCodec: Codec.AsObject[CompositeViewValue] =
    deriveConfiguredCodec[CompositeViewValue]

  implicit final private val compositeViewEventCodec: Codec.AsObject[CompositeViewEvent] =
    deriveConfiguredCodec[CompositeViewEvent]
}

object EventSerializer {
  final val compositeViewsEventManifest: String = CompositeViews.moduleType
}
