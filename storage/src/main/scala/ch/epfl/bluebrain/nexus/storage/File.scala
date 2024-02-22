package ch.epfl.bluebrain.nexus.storage

import akka.http.scaladsl.model.{ContentType, Uri}
import ch.epfl.bluebrain.nexus.storage.config.Contexts.resourceCtxIri
import ch.epfl.bluebrain.nexus.storage.jsonld.JsonLdContext.addContext
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto._
import io.circe.{Decoder, Encoder}

import scala.annotation.nowarn
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
    * @param filename
    *   the original filename of the file
    * @param mediaType
    *   the media type of the file
    */
  final case class FileDescription(filename: String, mediaType: ContentType)

  /**
    * Holds all the metadata information related to the file.
    *
    * @param location
    *   the file location
    * @param bytes
    *   the size of the file file in bytes
    * @param digest
    *   the digest information of the file
    * @param mediaType
    *   the media type of the file
    */
  final case class FileAttributes(location: Uri, bytes: Long, digest: Digest, mediaType: ContentType)
  object FileAttributes {
    import ch.epfl.bluebrain.nexus.delta.kernel.instances._

    @nowarn("cat=unused")
    implicit final private val uriDecoder: Decoder[Uri] =
      Decoder.decodeString.emapTry(s => Try(Uri(s)))

    @nowarn("cat=unused")
    implicit final private val uriEncoder: Encoder[Uri] =
      Encoder.encodeString.contramap(_.toString())

    implicit val fileAttrEncoder: Encoder[FileAttributes] =
      deriveConfiguredEncoder[FileAttributes].mapJson(addContext(_, resourceCtxIri))
    implicit val fileAttrDecoder: Decoder[FileAttributes] = deriveConfiguredDecoder[FileAttributes]
  }

  /**
    * Digest related information of the file
    *
    * @param algorithm
    *   the algorithm used in order to compute the digest
    * @param value
    *   the actual value of the digest of the file
    */
  final case class Digest(algorithm: String, value: String)

  object Digest {
    val empty: Digest                           = Digest("", "")
    implicit val digestEncoder: Encoder[Digest] = deriveConfiguredEncoder[Digest]
    implicit val digestDecoder: Decoder[Digest] = deriveConfiguredDecoder[Digest]
  }

}
// $COVERAGE-ON$
