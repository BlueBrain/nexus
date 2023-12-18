package ch.epfl.bluebrain.nexus.storage.routes

import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.storage._
import io.circe.Decoder
import io.circe.generic.semiauto.deriveDecoder

final case class CopyFile(sourceBucket: String, source: Uri.Path, destination: Uri.Path)
object CopyFile {
  implicit val dec: Decoder[CopyFile] = deriveDecoder
}
