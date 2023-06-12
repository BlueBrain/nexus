package ch.epfl.bluebrain.nexus.delta.plugins.archive

import ch.epfl.bluebrain.nexus.delta.plugins.archive.model.ArchiveFormat
import ch.epfl.bluebrain.nexus.delta.sdk.AkkaSource

class ZipDownloadSpec extends ArchiveDownloadSpec {
  override def format: ArchiveFormat[_] = ArchiveFormat.Zip

  override def sourceToMap(source: AkkaSource): Map[String, String] =
    fromZip(source).map { case (k, v) => k -> v.utf8String }

}
