package ch.epfl.bluebrain.nexus.delta.rdf.utils

trait SeqUtils {

  /**
    * @return Some(a) when the passed [[Seq]] has only one element, ''ifEmpty'' when the [[Seq]] is empty and None otherwise
    */
  def headOnlyOptionOr[A](seq: Seq[A])(ifEmpty: => A): Option[A] =
    seq.take(2).toList match {
      case Nil         => Some(ifEmpty)
      case head :: Nil => Some(head)
      case _           => None
    }
}

object SeqUtils extends SeqUtils
