package ch.epfl.bluebrain.nexus.kg.archives

import java.time.Clock

import ch.epfl.bluebrain.nexus.kg.archives.ArchiveSource.ArchiveSourceMetadata
import ch.epfl.bluebrain.nexus.kg.storage.AkkaSource

/**
  * Archive file
  *
  * @param bytes  size in bytes of the source
  * @param path   the path for the archived file
  * @param time   the modification time of the archived file
  * @param source the file content
  */
final case class ArchiveSource(bytes: Long, path: String, time: Long, source: AkkaSource) {
  def metadata: ArchiveSourceMetadata = ArchiveSourceMetadata(bytes, path, time)
}

object ArchiveSource {

  /**
    * Constructs an [[ArchiveSource]] using the provided ''bytes'', ''path'' and ''source''.
    * The modification time is retrieved from the provided implicit ''clock''
    */
  final def apply(bytes: Long, path: String, source: AkkaSource)(implicit clock: Clock): ArchiveSource =
    new ArchiveSource(bytes, path, clock.instant().getEpochSecond, source)

  /**
    * Archive file metadata
    *
    * @param bytes size in bytes of the file
    * @param path  the path for the archived file
    * @param time  the modification time of the archived file
    */
  final case class ArchiveSourceMetadata(bytes: Long, path: String, time: Long = System.currentTimeMillis())
}
