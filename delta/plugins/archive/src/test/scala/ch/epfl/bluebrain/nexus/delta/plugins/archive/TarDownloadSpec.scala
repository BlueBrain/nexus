package ch.epfl.bluebrain.nexus.delta.plugins.archive
import ch.epfl.bluebrain.nexus.delta.plugins.archive.model.ArchiveFormat
import ch.epfl.bluebrain.nexus.delta.sdk.AkkaSource

class TarDownloadSpec extends ArchiveDownloadSpec {
  override def format: ArchiveFormat[_] = ArchiveFormat.Tar

  override def sourceToMap(source: AkkaSource): Map[String, String] =
    fromTar(source).map { case (k, v) => k -> v.utf8String }

}
