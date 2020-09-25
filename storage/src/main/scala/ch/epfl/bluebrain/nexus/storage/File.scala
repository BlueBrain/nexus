package ch.epfl.bluebrain.nexus.storage

import akka.http.scaladsl.model.{ContentType, Uri}
import ch.epfl.bluebrain.nexus.delta.rdf.implicits._
import ch.epfl.bluebrain.nexus.storage.config.Contexts.resourceCtxIri
import scala.annotation.nowarn
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto._
import io.circe.{Decoder, Encoder}

import scala.util.Try

// $COVERAGE-OFF$
object File {

  @nowarn("cat=unused")
  implicit private val config: Configuration = Configuration.default
    .copy(transformMemberNames = {
      case "@context" => "@context"
      case key        => s"_$key"
    })

  /**
    * Holds some of the metadata information related to a file.
    *
    * @param filename  the original filename of the file
    * @param mediaType the media type of the file
    */
  final case class FileDescription(filename: String, mediaType: ContentType)

  /**
    * Holds all the metadata information related to the file.
    *
    * @param location  the file location
    * @param bytes     the size of the file file in bytes
    * @param digest    the digest information of the file
    * @param mediaType the media type of the file
    */
  final case class FileAttributes(location: Uri, bytes: Long, digest: Digest, mediaType: ContentType)
  object FileAttributes {
    @nowarn("cat=unused")
    implicit private val encMediaType: Encoder[ContentType] =
      Encoder.encodeString.contramap(_.value)

    @nowarn("cat=unused")
    implicit final private val uriDecoder: Decoder[Uri] =
      Decoder.decodeString.emapTry(s => Try(Uri(s)))

    @nowarn("cat=unused")
    implicit final private val uriEncoder: Encoder[Uri] =
      Encoder.encodeString.contramap(_.toString())

    @nowarn("cat=unused")
    implicit private val decMediaType: Decoder[ContentType] =
      Decoder.decodeString.emap(ContentType.parse(_).left.map(_.mkString("\n")))

    implicit val fileAttrEncoder: Encoder[FileAttributes] =
      deriveConfiguredEncoder[FileAttributes].mapJson(_.addContext(resourceCtxIri))
    implicit val fileAttrDecoder: Decoder[FileAttributes] = deriveConfiguredDecoder[FileAttributes]
  }

  /**
    * Digest related information of the file
    *
    * @param algorithm the algorithm used in order to compute the digest
    * @param value     the actual value of the digest of the file
    */
  final case class Digest(algorithm: String, value: String)

  object Digest {
    val empty: Digest                           = Digest("", "")
    implicit val digestEncoder: Encoder[Digest] = deriveConfiguredEncoder[Digest]
    implicit val digestDecoder: Decoder[Digest] = deriveConfiguredDecoder[Digest]
  }

}
// $COVERAGE-ON$
