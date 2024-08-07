package ch.epfl.bluebrain.nexus.ship

import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.kernel.utils.FileUtils
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import fs2.io.file.Path

object DirectoryReader {

  final private case class FileResult(headOffset: Option[Offset], head: Option[Path], tail: List[Path])

  private val empty = FileResult(None, None, List.empty)

  private def filenameAsOffset(path: Path) =
    FileUtils
      .filenameWithoutExtension(path.fileName.toString)
      .flatMap(_.toLongOption.map(Offset.at))

  /**
    * From the given files, filter out the ones where given the filename, no event is candidate by import:
    *   - All files where the filename is an offset greater than the provided one are conserved
    *   - The file with the offset immediately lower than the provided one is also conserved as it may contain some
    *     events to import
    *   - The conserved paths are sorted by name
    */
  def apply(paths: List[Path], fromOffset: Offset): List[Path] = {
    val result = paths.foldLeft(empty) {
      case (acc, path) if path.extName == ".json" =>
        filenameAsOffset(path).fold(acc) { newOffset =>
          if (newOffset >= fromOffset) {
            acc.copy(tail = acc.tail.appended(path))
          } else {
            if (acc.headOffset.isEmpty || acc.headOffset.exists(_ < newOffset)) {
              acc.copy(headOffset = Some(newOffset), head = Some(path))
            } else acc
          }
        }
      case (acc, _)                               => acc
    }

    (result.head.toList ++ result.tail).sortBy(_.fileName.toString)
  }

}
