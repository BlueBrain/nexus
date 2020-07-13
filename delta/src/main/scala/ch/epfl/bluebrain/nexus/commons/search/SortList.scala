package ch.epfl.bluebrain.nexus.commons.search

/**
  * Data type of a collection of [[Sort]]
  *
  * @param values the values to be sorted in the provided order
  */
final case class SortList(values: List[Sort])

object SortList {
  val Empty = SortList(List.empty)
}
