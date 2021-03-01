package ch.epfl.bluebrain.nexus.delta.sdk.model.search

import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv

/**
  * Data type of a collection of [[Sort]]
  *
  * @param values the values to be sorted in the provided order
  */
final case class SortList(values: List[Sort]) {
  def isEmpty: Boolean = values.isEmpty
}

object SortList {

  /**
    * Empty sort
    */
  val empty: SortList = SortList(List.empty)

  /**
    * sorted by ''createdAt'' first and secondarily by ''@id''
    */
  val byCreationDateAndId: SortList = SortList(List(Sort(nxv.createdAt.prefix), Sort("@id")))

}
