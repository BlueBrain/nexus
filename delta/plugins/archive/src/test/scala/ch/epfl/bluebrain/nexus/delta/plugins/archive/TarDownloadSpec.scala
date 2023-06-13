package ch.epfl.bluebrain.nexus.delta.plugins.archive
import ch.epfl.bluebrain.nexus.delta.plugins.archive.model.ArchiveFormat
import ch.epfl.bluebrain.nexus.delta.sdk.AkkaSource

import scala.concurrent.duration.DurationInt

class TarDownloadSpec extends ArchiveDownloadSpec {

  implicit override def patienceConfig: PatienceConfig = PatienceConfig(3.seconds, 10.millis)
  override def format: ArchiveFormat[_] = ArchiveFormat.Tar

  override def sourceToMap(source: AkkaSource): Map[String, String] =
    fromTar(source).map { case (k, v) => k -> v.utf8String }

}
