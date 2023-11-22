package ch.epfl.bluebrain.nexus.storage

import akka.http.scaladsl.model.Uri
import io.circe.Decoder
import io.circe.generic.semiauto.deriveDecoder

final case class CopyFile(source: Uri.Path, destination: Uri.Path)

object CopyFile {
  val thing: Decoder[Uri.Path]        = Decoder[Uri.Path]
  implicit val dec: Decoder[CopyFile] = deriveDecoder
}
