package ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model

import akka.http.scaladsl.model.ContentType
import akka.http.scaladsl.model.Uri.Path
import io.circe.Decoder
import io.circe.generic.extras.Configuration
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import io.circe.generic.extras.semiauto.deriveConfiguredDecoder

final case class FileLinkRequest(path: Path, mediaType: Option[ContentType], metadata: Option[FileCustomMetadata])

object FileLinkRequest {
  implicit private val config: Configuration                    = Configuration.default
  implicit val linkFileRequestDecoder: Decoder[FileLinkRequest] = deriveConfiguredDecoder[FileLinkRequest]
}
