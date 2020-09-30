package ch.epfl.bluebrain.nexus.delta.rdf.syntax

import ch.epfl.bluebrain.nexus.delta.rdf.utils.IterableUtils

trait IterableSyntax {
  implicit final def iterableOpsSyntax[A](seq: Iterable[A]): IterableOps[A] = new IterableOps(seq)
}

final class IterableOps[A](private val sequence: Iterable[A]) extends AnyVal {

  /**
    * @return Some(entry) where ''entry'' is the only available element on the sequence,
    *         Some(onEmpty) when the sequence has no elements,
    *         None otherwise
    */
  def singleEntryOr(onEmpty: => A): Option[A] =
    IterableUtils.singleEntryOr(sequence, onEmpty)

  /**
    * @return Some(entry) where ''entry'' is the only available element on the sequence,
    *         None otherwise
    */
  def singleEntry: Option[A] =
    IterableUtils.singleEntry(sequence)
}
