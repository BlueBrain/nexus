package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.serialization

import akka.actor.ExtendedActorSystem
import akka.serialization.SerializerWithStringManifest
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.ElasticSearchViews
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.{ElasticSearchViewEvent, ElasticSearchViewType, ElasticSearchViewValue}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.serialization.EventSerializer._
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
    case _: ElasticSearchViewEvent => elasticSearchViewsEventManifest
    case _                         =>
      throw new IllegalArgumentException(
        s"Unknown event type '${o.getClass.getCanonicalName}', expected ElasticSearchViewEvent"
      )
  }

  override def toBinary(o: AnyRef): Array[Byte] = o match {
    case e: ElasticSearchViewEvent => printer.print(e.asJson).getBytes(StandardCharsets.UTF_8)
    case _                         =>
      throw new IllegalArgumentException(
        s"Unknown event type '${o.getClass.getCanonicalName}', expected ElasticSearchViewEvent"
      )
  }

  override def fromBinary(bytes: Array[Byte], manifest: String): AnyRef = manifest match {
    case `elasticSearchViewsEventManifest` => parseAndDecode[ElasticSearchViewEvent](bytes, manifest)
    case _                                 =>
      throw new IllegalArgumentException(s"Unknown manifest '$manifest', expected '$elasticSearchViewsEventManifest'")
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

  implicit final private val elasticSearchViewTypeCodec: Codec.AsObject[ElasticSearchViewType] =
    deriveConfiguredCodec[ElasticSearchViewType]

  implicit final private val elasticSearchViewValueCodec: Codec.AsObject[ElasticSearchViewValue] =
    deriveConfiguredCodec[ElasticSearchViewValue]

  implicit final private val ElasticSearchViewEventCodec: Codec.AsObject[ElasticSearchViewEvent] =
    deriveConfiguredCodec[ElasticSearchViewEvent]
}

object EventSerializer {
  final val elasticSearchViewsEventManifest: String = ElasticSearchViews.moduleType
}
