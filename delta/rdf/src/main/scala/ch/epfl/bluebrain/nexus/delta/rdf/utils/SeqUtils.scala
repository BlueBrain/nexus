package ch.epfl.bluebrain.nexus.delta.rdf.utils

trait SeqUtils {

  /**
    * @return Some(a) when the passed [[Seq]] has only one element, None otherwise
    */
  def headOnlyOption[A](seq: Seq[A]): Option[A] =
    seq.take(2).toList match {
      case head :: Nil => Some(head)
      case _           => None
    }
}

object SeqUtils extends SeqUtils
