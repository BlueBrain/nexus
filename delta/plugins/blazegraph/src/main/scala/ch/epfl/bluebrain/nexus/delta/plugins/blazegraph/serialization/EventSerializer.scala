package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.serialization

import akka.actor.ExtendedActorSystem
import akka.serialization.SerializerWithStringManifest
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.BlazegraphViews
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.{BlazegraphViewEvent, BlazegraphViewType, BlazegraphViewValue, ViewRef}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.serialization.EventSerializer.blazegraphViewsEventManifest
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.sdk.model.Event
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredCodec
import io.circe.parser.decode
import io.circe.syntax._
import io.circe.{Codec, Decoder, Printer}

import java.nio.charset.StandardCharsets
import scala.annotation.nowarn

/**
  * A json serializer for Blazegraph plugin [[Event]] types.
  */
@nowarn("cat=unused")
@SuppressWarnings(Array("UnusedMethodParameter"))
class EventSerializer(system: ExtendedActorSystem) extends SerializerWithStringManifest {

  private val printer: Printer = Printer.noSpaces.copy(dropNullValues = true)

  override def identifier: Int = 453224

  override def manifest(o: AnyRef): String = o match {
    case _: BlazegraphViewEvent => blazegraphViewsEventManifest
    case _                      =>
      throw new IllegalArgumentException(
        s"Unknown event type '${o.getClass.getCanonicalName}', expected BlazegraphViewEvent"
      )
  }

  override def toBinary(o: AnyRef): Array[Byte] = o match {
    case e: BlazegraphViewEvent => printer.print(e.asJson).getBytes(StandardCharsets.UTF_8)
    case _                      =>
      throw new IllegalArgumentException(
        s"Unknown event type '${o.getClass.getCanonicalName}', expected BlazegraphViewEvent"
      )
  }

  override def fromBinary(bytes: Array[Byte], manifest: String): AnyRef = manifest match {
    case `blazegraphViewsEventManifest` => parseAndDecode[BlazegraphViewEvent](bytes, manifest)
    case _                              =>
      throw new IllegalArgumentException(s"Unknown manifest '$manifest', expected '$blazegraphViewsEventManifest'")
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

  implicit final private val viewRefEncoder: Codec.AsObject[ViewRef]                     = deriveConfiguredCodec[ViewRef]
  implicit final private val blazegraphViewTypeCodec: Codec.AsObject[BlazegraphViewType] =
    deriveConfiguredCodec[BlazegraphViewType]

  implicit final private val blazegraphViewValueCodec: Codec.AsObject[BlazegraphViewValue] =
    deriveConfiguredCodec[BlazegraphViewValue]

  implicit final private val blazegraphViewEventCodec: Codec.AsObject[BlazegraphViewEvent] =
    deriveConfiguredCodec[BlazegraphViewEvent]
}

object EventSerializer {
  final val blazegraphViewsEventManifest: String = BlazegraphViews.moduleType
}
