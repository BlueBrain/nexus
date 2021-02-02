package ch.epfl.bluebrain.nexus.migration.v1_4.serializer

import akka.http.scaladsl.model.{ContentType, Uri}
import cats.implicits._
import akka.serialization.SerializerWithStringManifest
import ch.epfl.bluebrain.nexus.migration.v1_4.events.EventDeserializationFailed
import ch.epfl.bluebrain.nexus.migration.v1_4.events.kg._
import io.circe.Decoder
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredDecoder
import io.circe.parser.decode

import java.nio.charset.Charset
import scala.annotation.nowarn
import scala.util.Try

/**
  * Akka ''SerializerWithStringManifest'' class definition for all events.
  * The serializer provides the types available for serialization.
  */
@nowarn("cat=unused") // private implicits in automatic derivation are not recognized as used
object KgEventSerializer {

  private val utf8 = Charset.forName("UTF-8")

  implicit private val config: Configuration = Configuration.default.withDiscriminator("@type")

  implicit private val uriDecoder: Decoder[Uri] = Decoder.decodeString.emapTry(uri => Try(Uri(uri)))

  implicit private val uriPathDecoder: Decoder[Uri.Path] = Decoder.decodeString.emapTry(uri => Try(Uri.Path(uri)))

  implicit private val decMediaType: Decoder[ContentType] =
    Decoder.decodeString.emap(ContentType.parse(_).left.map(_.mkString("\n")))

  implicit private val digestDecoder: Decoder[Digest] = deriveConfiguredDecoder[Digest]

  implicit private val fileAttributesDecoder: Decoder[FileAttributes]               = deriveConfiguredDecoder[FileAttributes]
  implicit private val storageFileAttributesDecoder: Decoder[StorageFileAttributes] =
    deriveConfiguredDecoder[StorageFileAttributes]

  implicit private val storageReferenceDecoder: Decoder[StorageReference] = deriveConfiguredDecoder[StorageReference]

  implicit val eventDecoder: Decoder[Event] = deriveConfiguredDecoder[Event]

  class EventSerializer extends SerializerWithStringManifest {

    final override def manifest(o: AnyRef): String =
      o match {
        case _: Event => "Event"
        case other    =>
          throw new IllegalArgumentException(
            s"Cannot determine manifest for unknown type: '${other.getClass.getCanonicalName}'"
          )
      }

    final override def toBinary(o: AnyRef): Array[Byte] = ???

    final override def fromBinary(bytes: Array[Byte], manifest: String): AnyRef = {
      val str = new String(bytes, utf8)
      manifest match {
        case "Event" =>
          decode[Event](str)
            .valueOr(error => EventDeserializationFailed(s"Cannot deserialize value to 'Event': ${error.show}", str))
        case other   =>
          EventDeserializationFailed(s"Cannot deserialize type with unknown manifest: '$other'", str)
      }
    }

    override val identifier: Int = 1050

  }
}
