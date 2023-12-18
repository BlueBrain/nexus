package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote.client.model

import akka.http.scaladsl.model.Uri.Path
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import io.circe.syntax.KeyOps
import io.circe.{Encoder, Json}

final case class RemoteDiskCopyPaths(
    sourceBucket: Label,
    sourcePath: Path,
    destPath: Path
)

object RemoteDiskCopyPaths {
  implicit val enc: Encoder[RemoteDiskCopyPaths] = Encoder.instance {
    case RemoteDiskCopyPaths(sourceBucket, source, dest) =>
      Json.obj("sourceBucket" := sourceBucket, "source" := source.toString(), "destination" := dest.toString())
  }
}
