package ch.epfl.bluebrain.nexus.utils

import akka.http.scaladsl.model.Uri.Path

import scala.annotation.tailrec

/**
  * Utilities around Akka Http Uri.Path
  */
trait PathUtils {

  /**
    * @return parent segment or end slash.
    *         E.g.: /a/b returns /a
    *         E.g.: / returns /
    */
  def parent(path: Path): Path = {
    @tailrec def inner(p: Path): Path =
      p match {
        case Path.Segment(_, Path.Slash(rest)) => rest
        case Path.Slash(rest)                  => inner(rest)
        case other                             => other
      }
    inner(path.reverse).reverse match {
      case Path.Empty => Path./
      case other      => other
    }
  }

  /**
    * Divides a path into a sequence of string segments
    */
  def segments(path: Path): Seq[String] = {
    @tailrec def inner(acc: Seq[String], rest: Path): Seq[String] =
      rest match {
        case Path.Empty                  => acc
        case Path.Slash(tail)            => inner(acc, tail)
        case Path.Segment(segment, tail) => inner(acc :+ segment, tail)
      }
    inner(Vector.empty, path)
  }

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
