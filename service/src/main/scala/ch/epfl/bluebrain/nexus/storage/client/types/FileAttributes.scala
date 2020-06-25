package ch.epfl.bluebrain.nexus.storage.client.types

import akka.http.scaladsl.model.{ContentType, Uri}
import ch.epfl.bluebrain.nexus.storage.client.types.FileAttributes.Digest
import io.circe.Decoder
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto._

import scala.annotation.nowarn
import scala.util.Try

// $COVERAGE-OFF$
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
  implicit private val config: Configuration =
    Configuration.default
      .copy(transformMemberNames = {
        case "@context" => "@context"
        case key        => s"_$key"
      })

  implicit val fileAttrDecoder: Decoder[FileAttributes] = {
    @nowarn("cat=unused")
    implicit val decUri: Decoder[Uri]               =
      Decoder.decodeString.emapTry(s => Try(Uri(s)))
    @nowarn("cat=unused")
    implicit val decMediaType: Decoder[ContentType] =
      Decoder.decodeString.emap(ContentType.parse(_).left.map(_.mkString("\n")))
    deriveConfiguredDecoder[FileAttributes]
  }

  /**
    * Digest related information of the file
    *
    * @param algorithm the algorithm used in order to compute the digest
    * @param value     the actual value of the digest of the file
    */
  final case class Digest(algorithm: String, value: String)

  object Digest {
    val empty: Digest = Digest("", "")

    implicit val digestDecoder: Decoder[Digest] = deriveConfiguredDecoder[Digest]
  }
}
// $COVERAGE-ON$
