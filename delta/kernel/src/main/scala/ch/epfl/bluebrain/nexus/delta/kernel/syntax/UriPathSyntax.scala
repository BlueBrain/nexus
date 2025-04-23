package ch.epfl.bluebrain.nexus.delta.kernel.syntax

import org.http4s.Uri

trait UriPathSyntax {
  implicit final def uriPathSyntax(path: Uri.Path): UriPathOps = new UriPathOps(path)
}

final class UriPathOps(private val path: Uri.Path) extends AnyVal {

  def lastSegment: Option[String] = path.segments.lastOption.map(_.toString)

}
