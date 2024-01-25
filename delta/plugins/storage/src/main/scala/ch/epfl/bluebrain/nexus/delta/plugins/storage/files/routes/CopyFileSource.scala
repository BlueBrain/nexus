package ch.epfl.bluebrain.nexus.delta.plugins.storage.files.routes

import cats.data.NonEmptyList
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{ProjectRef, ResourceRef}
import io.circe.Decoder

final case class CopyFileSource(
    project: ProjectRef,
    files: NonEmptyList[ResourceRef]
)

object CopyFileSource {
  implicit val dec: Decoder[CopyFileSource] =
    Decoder.forProduct2("sourceProject", "files")(CopyFileSource.apply)
}
