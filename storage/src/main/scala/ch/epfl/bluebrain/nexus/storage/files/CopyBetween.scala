package ch.epfl.bluebrain.nexus.storage.files

import fs2.io.file.Path
import io.circe.Encoder
import ch.epfl.bluebrain.nexus.storage._
import io.circe.generic.semiauto.deriveEncoder

final case class CopyBetween(source: Path, destination: Path)

object CopyBetween {
  implicit val enc: Encoder[CopyBetween] = deriveEncoder
}
