package ch.epfl.bluebrain.nexus.storage.files

import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.storage.encUriPath
import io.circe.Encoder
import io.circe.generic.semiauto.deriveEncoder

import java.nio.file.Path

final case class CopyFileOutput(
    sourcePath: Uri.Path,
    destinationPath: Uri.Path,
    absoluteSourceLocation: Path,
    absoluteDestinationLocation: Path
)

object CopyFileOutput {
  implicit val pathEnc: Encoder[Path]       = Encoder[String].contramap[Path](_.toString)
  implicit val enc: Encoder[CopyFileOutput] = deriveEncoder
}
