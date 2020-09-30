package ch.epfl.bluebrain.nexus.delta.rdf.utils

trait IterableUtils {

  /**
    * @return Some(entry) where ''entry'' is the only available element on the sequence,
    *         Some(onEmpty) when the sequence has no elements,
    *         None otherwise
    */
  def singleEntryOr[A](sequence: Iterable[A], onEmpty: => A): Option[A] =
    sequence.take(2).toList match {
      case Nil         => Some(onEmpty)
      case head :: Nil => Some(head)
      case _           => None
    }

  /**
    * @return Some(entry) where ''entry'' is the only available element on the sequence,
    *         None otherwise
    */
  def singleEntry[A](sequence: Iterable[A]): Option[A] =
    sequence.take(2).toList match {
      case head :: Nil => Some(head)
      case _           => None
    }
}

object IterableUtils extends IterableUtils
