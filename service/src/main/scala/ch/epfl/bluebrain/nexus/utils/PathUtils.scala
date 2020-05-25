package ch.epfl.bluebrain.nexus.utils

import akka.http.scaladsl.model.Uri.Path

/**
  * Utilities around Akka Http Uri.Path
  */
trait PathUtils {

  /**
    * @return Some(segment) with the last segment of the path if the path ends with a segment,
    *         None otherwise
    */
  def lastSegment(path: Path): Option[String] =
    path.reverse match {
      case _: Path.SlashOrEmpty  => None
      case Path.Segment(head, _) => Some(head)
    }
}

object PathUtils extends PathUtils
