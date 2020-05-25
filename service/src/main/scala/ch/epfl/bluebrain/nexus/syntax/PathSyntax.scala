package ch.epfl.bluebrain.nexus.syntax

import akka.http.scaladsl.model.Uri.Path
import ch.epfl.bluebrain.nexus.utils.PathUtils

trait PathSyntax {
  implicit def uriPathUtilsSyntax(path: Path) = new PathUtilsOps(path)
}

final class PathUtilsOps(private val path: Path) extends AnyVal {

  def lastSegment: Option[String] =
    PathUtils.lastSegment(path)

}
