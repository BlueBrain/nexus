package ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model

import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import io.circe.Decoder
import io.circe.generic.semiauto.deriveDecoder

case class FileUserMetadata(keywords: Map[Label, String])

object FileUserMetadata {

  implicit val decoder: Decoder[FileUserMetadata] = deriveDecoder[FileUserMetadata]
}
