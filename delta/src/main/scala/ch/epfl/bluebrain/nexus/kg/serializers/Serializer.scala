package ch.epfl.bluebrain.nexus.kg.serializers

import akka.actor.ExtendedActorSystem
import akka.http.scaladsl.model.Uri
import akka.serialization.SerializerWithStringManifest
import cats.syntax.show._
import ch.epfl.bluebrain.nexus.commons.serialization.AkkaCoproductSerializer
import ch.epfl.bluebrain.nexus.kg.resources.Event.{Created, FileCreated}
import ch.epfl.bluebrain.nexus.kg.resources.ProjectIdentifier.ProjectRef
import ch.epfl.bluebrain.nexus.kg.resources.ProjectIdentifier.ProjectRef._
import ch.epfl.bluebrain.nexus.kg.resources._
import ch.epfl.bluebrain.nexus.kg.resources.file.File._
import ch.epfl.bluebrain.nexus.kg.resources.syntax._
import ch.epfl.bluebrain.nexus.rdf.Iri.AbsoluteIri
import ch.epfl.bluebrain.nexus.rdf.implicits._
import ch.epfl.bluebrain.nexus.delta.config.AppConfig.HttpConfig
import ch.epfl.bluebrain.nexus.delta.config.{AppConfig, Settings}
import ch.epfl.bluebrain.nexus.storage.client.types.FileAttributes.{Digest => StorageDigest}
import ch.epfl.bluebrain.nexus.storage.client.types.{FileAttributes => StorageFileAttributes}

import scala.annotation.nowarn
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.{deriveConfiguredDecoder, deriveConfiguredEncoder}
import io.circe.syntax._
import io.circe.{Decoder, Encoder, Json}
import shapeless.{:+:, CNil}

import scala.util.Try

/**
  * Akka ''SerializerWithStringManifest'' class definition for all events.
  * The serializer provides the types available for serialization.
  */
@nowarn("cat=unused") // private implicits in automatic derivation are not recognized as used
object Serializer {

  implicit private val config: Configuration = Configuration.default.withDiscriminator("@type")

  implicit private val refEncoder: Encoder[Ref] = Encoder.encodeString.contramap(_.iri.show)
  implicit private val refDecoder: Decoder[Ref] = absoluteIriDecoder.map(Ref(_))

  implicit private val uriEncoder: Encoder[Uri] = Encoder.encodeString.contramap(_.toString)
  implicit private val uriDecoder: Decoder[Uri] = Decoder.decodeString.emapTry(uri => Try(Uri(uri)))

  implicit private val uriPathEncoder: Encoder[Uri.Path] = Encoder.encodeString.contramap(_.toString)
  implicit private val uriPathDecoder: Decoder[Uri.Path] = Decoder.decodeString.emapTry(uri => Try(Uri.Path(uri)))

  implicit private val digestEncoder: Encoder[Digest] = deriveConfiguredEncoder[Digest]
  implicit private val digestDecoder: Decoder[Digest] = deriveConfiguredDecoder[Digest]

  implicit private val storageDigestEncoder: Encoder[StorageDigest] = deriveConfiguredEncoder[StorageDigest]
  implicit private val storageDigestDecoder: Decoder[StorageDigest] = deriveConfiguredDecoder[StorageDigest]

  implicit private val storageFileAttributesEncoder: Encoder[StorageFileAttributes] =
    deriveConfiguredEncoder[StorageFileAttributes]
  implicit private val storageFileAttributesDecoder: Decoder[StorageFileAttributes] =
    deriveConfiguredDecoder[StorageFileAttributes]

  implicit private val fileAttributesEncoder: Encoder[FileAttributes] = deriveConfiguredEncoder[FileAttributes]
  implicit private val fileAttributesDecoder: Decoder[FileAttributes] = deriveConfiguredDecoder[FileAttributes]

  implicit private val storageReferenceEncoder: Encoder[StorageReference] = deriveConfiguredEncoder[StorageReference]
  implicit private val storageReferenceDecoder: Decoder[StorageReference] = deriveConfiguredDecoder[StorageReference]

  implicit private val encodeResId: Encoder[ResId] =
    Encoder.forProduct2("project", "id")(r => (r.parent.id, r.value.show))

  implicit private val decodeResId: Decoder[ResId] =
    Decoder.forProduct2("project", "id") { (projRef: ProjectRef, id: AbsoluteIri) =>
      Id(projRef, id)
    }

  implicit def eventEncoder(implicit http: AppConfig.HttpConfig): Encoder[Event] = {
    val enc = deriveConfiguredEncoder[Event]
    Encoder.instance { ev =>
      val json = enc(ev).removeKeys("id") deepMerge ev.id.asJson
      ev match {
        case _: FileCreated | _: Created => json deepMerge Json.obj("rev" -> Json.fromLong(1L))
        case _                           => json
      }
    }
  }

  implicit val eventDecoder: Decoder[Event] = {
    val dec = deriveConfiguredDecoder[Event]
    Decoder.instance { hc =>
      val json = hc.value
      for {
        key     <- json.as[ResId]
        combined = json deepMerge Json.obj("id" -> key.asJson)
        event   <- dec(combined.hcursor)
      } yield event
    }
  }

  class EventSerializer(system: ExtendedActorSystem) extends SerializerWithStringManifest {

    implicit private val config: AppConfig = Settings(system).appConfig
    implicit private val http: HttpConfig  = config.http

    private val serializer = new AkkaCoproductSerializer[Event :+: CNil](1050)

    final override def manifest(o: AnyRef): String = serializer.manifest(o)

    final override def toBinary(o: AnyRef): Array[Byte] = serializer.toBinary(o)

    final override def fromBinary(bytes: Array[Byte], manifest: String): AnyRef = serializer.fromBinary(bytes, manifest)

    override val identifier: Int = serializer.identifier

  }
}
