package ch.epfl.bluebrain.nexus.delta.rdf.syntax

import ch.epfl.bluebrain.nexus.delta.rdf.utils.PathUtils

import java.nio.file.Path

trait PathSyntax {
  implicit final def javaPathSyntax(path: Path): JavaPathOps = new JavaPathOps(path)
}

final class JavaPathOps(private val path: Path) extends AnyVal {

  /**
    * Checks if the current path is a descendant of the ''parent'' path
    * E.g.: path = /some/my/path ; parent = /some will return true
    * E.g.: path = /some/my/path ; parent = /other will return false
    */
  def descendantOf(parent: Path): Boolean = PathUtils.descendantOf(path, parent)
}
