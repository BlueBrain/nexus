package ch.epfl.bluebrain.nexus.delta.sdk.model.search

/**
  * Data type of a collection of [[Sort]]
  *
  * @param values the values to be sorted in the provided order
  */
final case class SortList(values: List[Sort]) {
  def isEmpty: Boolean = values.isEmpty
}

object SortList {
  val empty = SortList(List.empty)
}
